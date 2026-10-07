package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.model.GateResult;
import io.agentflow.model.Node;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * Entry and exit gates. A spec is "name" or "name:arg". Entry gates decide whether a node may start;
 * exit gates decide whether its output is accepted. Human gates park a node instead of failing it.
 */
public final class Gates {

    public record Context(Node node, ContextStore store, Workspace workspace, JsonNode scenario, Path runDir) {
        JsonNode book() {
            return Playbooks.load(scenario.get("playbook").asText());
        }
    }

    private static final Map<String, BiFunction<Context, String, GateResult>> GATES = new HashMap<>();

    private Gates() {
    }

    public static GateResult evaluate(String spec, Context ctx) {
        String name = spec.contains(":") ? spec.substring(0, spec.indexOf(':')) : spec;
        String arg = spec.contains(":") ? spec.substring(spec.indexOf(':') + 1) : "";
        BiFunction<Context, String, GateResult> gate = GATES.get(name);
        if (gate == null) {
            return GateResult.of(false, "unknown gate '" + name + "'").named(spec);
        }
        try {
            return gate.apply(ctx, arg).named(spec);
        } catch (RuntimeException e) {
            return GateResult.of(false, "gate error: " + e).named(spec); // a crashing gate never passes
        }
    }

    static {
        GATES.put("artifact", (g, name) -> GateResult.of(g.store().has(name),
                "artifact '" + name + "' " + (g.store().has(name) ? "present" : "missing")));

        GATES.put("clarified", (g, qid) -> {
            JsonNode req = g.store().get("requirements");
            boolean open = !req.has("blocking_open") || contains(req.get("blocking_open"), qid);
            String detail = open ? "waiting for a human answer to " + qid + ": "
                    + questionText(req, qid) : qid + " answered";
            return new GateResult("", !open, detail, true);
        });

        GATES.put("requirements_valid", (g, a) -> {
            JsonNode caps = g.store().get("requirements").get("capabilities");
            List<String> bad = new ArrayList<>();
            caps.forEach(c -> {
                if (c.get("acceptance_criteria").isEmpty()) {
                    bad.add(c.get("id").asText());
                }
            });
            boolean ok = !caps.isEmpty() && bad.isEmpty();
            return GateResult.of(ok, ok ? caps.size() + " capabilities, all with acceptance criteria"
                    : "capabilities without acceptance criteria: " + bad);
        });

        GATES.put("design_covers_requirements", (g, a) -> {
            JsonNode design = g.store().get("design");
            List<String> missing = new ArrayList<>();
            g.store().get("requirements").get("capabilities").forEach(c -> {
                if (!design.get("components").has(c.get("id").asText())) {
                    missing.add(c.get("id").asText());
                }
            });
            return GateResult.of(missing.isEmpty(), missing.isEmpty() ? "every capability has a design element"
                    : "no design for " + missing);
        });

        GATES.put("plan_valid", (g, a) -> {
            JsonNode plan = g.store().get("plan");
            Set<String> ids = new TreeSet<>();
            plan.get("tasks").forEach(t -> ids.add(t.get("id").asText()));
            List<String> bad = new ArrayList<>();
            plan.get("tasks").forEach(t -> t.get("deps").forEach(d -> {
                if (!ids.contains(d.asText())) {
                    bad.add(t.get("id").asText() + "->" + d.asText());
                }
            }));
            if (!bad.isEmpty()) {
                return GateResult.of(false, "tasks depend on unknown tasks " + bad);
            }
            if (!plan.get("uncovered").isEmpty()) {
                return GateResult.of(false, "capabilities without tasks: " + plan.get("uncovered"));
            }
            return GateResult.of(true, ids.size() + " tasks; every capability traced to at least one task");
        });

        // One Maven run: compile main + tests, Checkstyle, then the task's declared tests. Every declared
        // test must exist, and must appear in the execution evidence as run and passed.
        GATES.put("build", (g, a) -> {
            List<String> expected = g.node().verify();
            List<String> missing = expected.stream().filter(p -> !g.workspace().exists(p)).toList();
            if (!missing.isEmpty()) {
                return GateResult.of(false, "expected tests are missing: " + missing);
            }
            List<String> tests = expected.stream().map(Maven::testClass).distinct().toList();
            Path reports = g.workspace().root.resolve("target/surefire-reports");
            SurefireReports.clear(reports); // stale reports must never count as evidence
            List<String> args = new ArrayList<>(List.of("test-compile",
                    "org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check"));
            if (!tests.isEmpty()) {
                args.addAll(List.of("surefire:test", "-Dtest=" + String.join(",", tests), "-Djacoco.skip=true"));
            }
            Maven.Result r = Maven.mvn(g.workspace().root, 900, args);
            Map<String, SurefireReports.ClassResult> results = SurefireReports.read(reports);
            if (!r.ok()) {
                List<String> failing = new ArrayList<>();
                results.values().forEach(cr -> failing.addAll(cr.failed()));
                return GateResult.of(false, classify(r) + (failing.isEmpty() ? "" : " | failing tests: " + failing));
            }
            List<String> evidence = new ArrayList<>();
            for (String t : tests) {
                SurefireReports.ClassResult cr = results.get(t);
                if (cr == null || !cr.green()) {
                    return GateResult.of(false, "no execution evidence that " + t + " ran and passed"
                            + (cr == null ? " (no report)" : " (" + cr.tests() + " run, " + cr.failures() + " failed)"));
                }
                evidence.add(t + " " + cr.passed().size() + "/" + cr.tests());
            }
            return GateResult.of(true, "compiled, checkstyle clean" + (evidence.isEmpty() ? ""
                    : ", executed: " + String.join(", ", evidence)) + "; sandbox " + Sandbox.mode().name().toLowerCase());
        });

        GATES.put("tests_pass", (g, a) -> {
            JsonNode rep = g.store().get("test_report");
            boolean ok = rep.get("ok").asBoolean() && rep.get("failed").asInt() == 0 && rep.get("total").asInt() > 0;
            return GateResult.of(ok, rep.get("passed") + " passed, " + rep.get("failed") + " failed, "
                    + rep.get("errors") + " errors" + (rep.get("failures").isEmpty() ? "" : " | " + rep.get("failures")));
        });

        GATES.put("coverage_min", (g, arg) -> {
            double min = arg.isEmpty() ? g.book().path("coverage_min").asDouble(80) : Double.parseDouble(arg);
            double cov = g.store().get("test_report").path("coverage").asDouble(0);
            return GateResult.of(cov >= min, "line coverage " + cov + "% (min " + min + "%)");
        });

        GATES.put("lint_clean", (g, a) -> {
            JsonNode lint = g.store().get("review_report").get("lint");
            return GateResult.of(lint.isEmpty(), lint.isEmpty() ? "no checkstyle violations" : lint.toString());
        });

        GATES.put("security_clean", (g, a) -> {
            JsonNode sec = g.store().get("review_report").get("security");
            return GateResult.of(sec.isEmpty(), sec.isEmpty() ? "no security findings" : sec.toString());
        });

        GATES.put("openapi_valid", (g, a) -> {
            JsonNode spec = Json.parse(g.workspace().read("openapi.json"));
            Set<String> have = new TreeSet<>();
            spec.get("paths").fields().forEachRemaining(p -> p.getValue().fieldNames()
                    .forEachRemaining(m -> have.add(m.toUpperCase() + " " + p.getKey())));
            List<String> missing = new ArrayList<>();
            g.store().get("design").get("endpoints").forEach(e -> {
                if (!have.contains(normalise(e.asText()))) {
                    missing.add(e.asText());
                }
            });
            boolean ok = missing.isEmpty() && spec.path("openapi").asText().startsWith("3");
            return GateResult.of(ok, ok ? have.size() + " operations; all designed endpoints present"
                    : "designed but not implemented: " + missing);
        });

        GATES.put("docs_complete", (g, a) -> {
            List<String> missing = new ArrayList<>();
            g.store().get("docs").get("files").forEach(f -> {
                if (!g.workspace().exists(f.asText())) {
                    missing.add(f.asText());
                }
            });
            return GateResult.of(missing.isEmpty(), missing.isEmpty()
                    ? g.store().get("docs").get("files").size() + " docs written" : "missing " + missing);
        });

        GATES.put("readiness", (g, a) -> {
            JsonNode rd = g.store().get("release_readiness");
            List<String> failing = new ArrayList<>();
            rd.get("checks").forEach(c -> {
                if (!c.get("passed").asBoolean()) {
                    failing.add(c.get("check").asText());
                }
            });
            return GateResult.of(rd.get("ready").asBoolean(), failing.isEmpty() ? "all release checks pass"
                    : "failing: " + failing);
        });

        // Package the JAR, start it on a free port and drive the playbook's smoke steps over HTTP.
        GATES.put("smoke", (g, a) -> {
            String err = AppRunner.packageJar(g.workspace().root);
            if (err != null) {
                return GateResult.of(false, err);
            }
            JsonNode book = g.book();
            Map<String, String> props = new LinkedHashMap<>();
            book.path("smoke_props").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue().asText()));
            List<String> log = new ArrayList<>();
            try (AppRunner app = AppRunner.start(g.workspace().root, props, "smoke")) {
                Map<String, String> saved = new HashMap<>();
                for (JsonNode step : book.get("smoke")) {
                    String path = step.get("path").asText();
                    for (Map.Entry<String, String> s : saved.entrySet()) {
                        path = path.replace("{" + s.getKey() + "}", s.getValue());
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    step.path("headers").fields().forEachRemaining(h -> headers.put(h.getKey(), h.getValue().asText()));
                    HttpResponse<String> resp = app.request(step.get("method").asText(), path,
                            step.has("json") ? Json.write(step.get("json")) : null, headers);
                    String line = step.get("method").asText() + " " + path + " -> " + resp.statusCode();
                    if (resp.statusCode() != step.get("expect").asInt()) {
                        log.add(line + " (expected " + step.get("expect").asInt() + ") " + resp.body());
                        return GateResult.of(false, String.join("; ", log));
                    }
                    log.add(line);
                    if (step.has("save")) {
                        String key = step.get("save").asText();
                        saved.put(key, Json.parse(resp.body()).get(key).asText());
                    }
                }
            } catch (IOException e) {
                return GateResult.of(false, "smoke failed: " + e.getMessage());
            }
            return GateResult.of(true, "packaged JAR started; " + String.join("; ", log));
        });
    }

    /** Spring path variables are {name}; the design records use the same form. */
    static String questionText(JsonNode req, String qid) {
        for (JsonNode q : req.path("questions")) {
            if (q.path("id").asText().equals(qid)) {
                return q.path("text").asText();
            }
        }
        JsonNode known = Knowledge.question(qid);
        return known == null ? qid : known.get("text").asText();
    }

    static String normalise(String endpoint) {
        return endpoint;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode v : array) {
            if (v.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Names the phase that failed and keeps the lines a reviewer (or a model) needs to fix it. */
    static String classify(Maven.Result r) {
        String out = r.output();
        List<String> keep = new ArrayList<>();
        for (String line : out.split("\n")) {
            String l = line.strip();
            if ((l.startsWith("[ERROR]") && (l.contains(".java") || l.contains("Tests run") || l.contains("FAIL")))
                    || l.startsWith("[WARN]") || l.contains("expected:") || l.contains("but was") || l.startsWith("Caused by")) {
                keep.add(l.replace("[ERROR] ", ""));
            }
        }
        String phase = out.contains("COMPILATION ERROR") ? "compile failed"
                : out.contains("Checkstyle violation") ? "lint (checkstyle) failed"
                : out.contains("Tests run:") ? "tests failed"
                : r.code() == 124 ? "build timed out" : "build failed";
        List<String> top = keep.stream().distinct().limit(8).toList();
        return phase + (top.isEmpty() ? ":\n" + r.tail(10) : ": " + String.join(" | ", top));
    }
}
