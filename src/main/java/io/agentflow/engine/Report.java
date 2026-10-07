package io.agentflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.AuditLog;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Json;
import io.agentflow.core.Metrics;
import io.agentflow.model.Decision;
import io.agentflow.model.Node;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Human-readable run report (runs/&lt;id&gt;/report.md) plus exported artifacts. Everything is derived
 * from state.json and the audit trail, so the report is a view of the evidence, not a separate claim.
 */
public final class Report {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Set<String> TIMELINE = Set.of("run.session.start", "run.session.end", "attempt.start",
            "attempt.retry", "change.applied", "change.rolled_back", "change.reverted", "change.revert_refused",
            "policy.blocked", "approval.requested", "approval.decided", "human.answered", "node.waiting",
            "node.invalidated", "node.reused", "node.superseded", "plan.created", "plan.revised", "node.failed",
            "run.safe_stop", "agent.note");

    private Report() {
    }

    private static String ts(Double t) {
        return t == null ? "" : TIME.format(Instant.ofEpochMilli((long) (t * 1000)));
    }

    private static String cell(Object o) {
        return String.valueOf(o == null ? "" : o).replace("|", "\\|").replace("\n", " ");
    }

    private static void table(List<String> out, List<String> headers, List<List<Object>> rows) {
        out.add("| " + String.join(" | ", headers) + " |");
        out.add("|" + "---|".repeat(headers.size()));
        rows.forEach(r -> out.add("| " + String.join(" | ", r.stream().map(Report::cell).toList()) + " |"));
    }

    private static String join(JsonNode array, String sep) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.isTextual() ? v.asText() : v.toString()));
        return String.join(sep, out);
    }

    public static Path write(Path dir) {
        RunState st = new RunState(dir);
        List<JsonNode> events = AuditLog.read(dir.resolve("audit.jsonl"));
        Map<String, Object> m = Metrics.compute(dir.resolve("audit.jsonl"));
        ContextStore store = st.store;
        try {
            Files.writeString(dir.resolve("metrics.json"), Json.pretty(m));
            for (Map.Entry<String, List<ContextStore.ArtifactVersion>> e : store.artifacts.entrySet()) {
                Files.writeString(dir.resolve("artifacts").resolve(e.getKey() + ".json"), Json.pretty(e.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode sc = st.scenario;
        JsonNode req = store.get("requirements");
        List<String> out = new ArrayList<>(List.of("# Run " + st.runId, "",
                "**Scenario:** " + sc.get("id").asText() + ": " + sc.get("title").asText() + "  ",
                "**Status:** `" + st.status + "`" + (st.stopReason != null ? " (" + st.stopReason + ")" : "") + "  ",
                "**Provider:** " + sc.path("provider").asText("deterministic") + " · **Playbook:** "
                        + sc.get("playbook").asText(), "", "## 1. Requirement", "", "```text",
                sc.get("requirement").asText().strip(), "```", ""));

        if (!req.isMissingNode()) {
            out.addAll(List.of("## 2. Requirement understanding", ""));
            List<List<Object>> rows = new ArrayList<>();
            req.get("capabilities").forEach(c -> rows.add(Arrays.asList(c.get("id").asText(), c.get("source").asText(),
                    join(c.get("acceptance_criteria"), "<br>"))));
            table(out, List.of("Capability", "Evidence in request", "Acceptance criteria"), rows);
            if (!req.get("questions").isEmpty()) {
                out.addAll(List.of("", "**Ambiguities detected**", ""));
                List<List<Object>> qrows = new ArrayList<>();
                req.get("questions").forEach(q -> {
                    JsonNode res = req.get("resolved").get(q.get("id").asText());
                    String status = res != null ? res.get("option").asText() + " (" + res.get("source").asText() + ")"
                            : q.get("blocking").asBoolean() ? "OPEN, blocking" : "open";
                    List<String> opts = new ArrayList<>();
                    q.get("options").fieldNames().forEachRemaining(opts::add);
                    qrows.add(Arrays.asList(q.get("id").asText(), q.get("evidence").asText(), q.get("text").asText(),
                            String.join(", ", opts), status));
                });
                table(out, List.of("Id", "Trigger", "Question", "Options", "Resolution"), qrows);
            }
            for (String[] sec : new String[][] {{"Assumptions", "assumptions"}, {"Out of scope", "out_of_scope"}}) {
                if (!req.get(sec[1]).isEmpty()) {
                    out.addAll(List.of("", "**" + sec[0] + "**", ""));
                    req.get(sec[1]).forEach(x -> out.add("- " + x.asText()));
                }
            }
            out.add("");
        }

        JsonNode impact = store.get("impact_analysis");
        if (!impact.isMissingNode()) {
            out.addAll(List.of("## 3. Codebase reasoning (brownfield)", "",
                    "- Class dependency graph: `" + impact.get("import_graph") + "`",
                    "- Blast radius: " + impact.get("blast_radius"),
                    "- Data at risk: " + impact.get("data_at_risk") + " (schema tables: " + impact.get("schema_tables") + ")",
                    "- Regression tests selected: " + impact.get("regression_tests"), ""));
            List<List<Object>> rows = new ArrayList<>();
            impact.get("impacted").fields().forEachRemaining(e -> rows.add(Arrays.asList(e.getKey(),
                    e.getValue().get("modules"), e.getValue().get("routes"), e.getValue().get("tables"))));
            table(out, List.of("Capability", "Classes", "Routes", "Tables"), rows);
            if (!impact.get("findings").isEmpty()) {
                out.addAll(List.of("", "**Latent defects found by static analysis**", ""));
                impact.get("findings").forEach(f -> out.add("- `" + f.get("module").asText() + "." + f.get("function")
                        .asText() + "`: " + f.get("kind").asText() + ": " + f.get("detail").asText()));
            }
            out.add("");
        }

        JsonNode design = store.get("design");
        if (!design.isMissingNode()) {
            out.addAll(List.of("## 4. Design", "", "Style: " + design.get("style").asText(), ""));
            List<List<Object>> rows = new ArrayList<>();
            design.get("adrs").forEach(a -> rows.add(Arrays.asList(a.get("id").asText(), a.get("title").asText(),
                    a.get("rationale").asText(), join(a.get("alternatives"), "; "))));
            table(out, List.of("ADR", "Decision", "Rationale", "Alternatives"), rows);
            out.add("");
        }

        JsonNode plan = store.get("plan");
        if (!plan.isMissingNode()) {
            out.addAll(List.of("## 5. Plan (v" + store.version("plan") + ") and decomposition", ""));
            List<List<Object>> rows = new ArrayList<>();
            plan.get("tasks").forEach(t -> rows.add(Arrays.asList(t.get("id").asText(), t.get("agent").asText(),
                    t.get("deps").isEmpty() ? "-" : join(t.get("deps"), ", "),
                    t.get("writes").isEmpty() ? "-" : join(t.get("writes"), ", ").replaceAll("src/(main|test)/java/[^ ,]*/", ""),
                    t.get("verify").isEmpty() ? "-" : join(t.get("verify"), ", ").replaceAll("src/test/java/[^ ,]*/", ""),
                    t.get("impact").asText())));
            table(out, List.of("Task", "Agent", "Depends on", "Writes", "Verified by", "Declared impact"), rows);
            out.addAll(List.of("", "**Traceability (capability → tasks)**", ""));
            plan.get("traceability").fields().forEachRemaining(e -> out.add("- " + e.getKey() + ": "
                    + (e.getValue().isEmpty() ? "not covered" : e.getValue().toString())));
            out.add("");
        }

        out.addAll(List.of("## 6. Orchestration graph", "", "```mermaid", st.graph.toMermaid(), "```", "",
                "Rectangles are stages and tasks; diamonds are high-impact nodes that require human approval.", "",
                "## 7. Execution", ""));
        List<List<Object>> exec = new ArrayList<>();
        for (String id : st.graph.topologicalOrder()) {
            Node n = st.graph.get(id);
            String note = n.waitReason != null ? n.waitReason : n.error != null ? n.error : "";
            exec.add(Arrays.asList(n.id, n.kind, n.status.value(), n.attempts, n.runs, n.origin, ts(n.startedAt),
                    ts(n.finishedAt), note.length() > 160 ? note.substring(0, 160) : note));
        }
        table(out, List.of("Node", "Kind", "Status", "Attempts", "Runs", "Origin", "Started", "Finished", "Note"), exec);
        out.addAll(List.of("", "**Timeline (audit trail excerpts)**", ""));
        for (JsonNode e : events) {
            if (TIMELINE.contains(e.get("event").asText())) {
                JsonNode d = e.get("data").deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) d).remove("findings");
                String text = Json.write(d);
                out.add("- `" + ts(e.get("ts").asDouble()) + "` **" + e.get("event").asText() + "** "
                        + e.path("node").asText("") + " " + (text.length() > 220 ? text.substring(0, 220) : text));
            }
        }
        out.addAll(List.of("", "## 8. Validation gates", ""));
        List<List<Object>> gates = new ArrayList<>();
        events.stream().filter(e -> e.get("event").asText().startsWith("gate.")).forEach(e -> {
            String detail = e.get("data").get("detail").asText();
            gates.add(Arrays.asList(e.path("node").asText(), e.get("event").asText().substring(5), e.get("data").get("gate")
                    .asText(), e.get("data").get("passed").asBoolean() ? "pass" : "FAIL",
                    detail.length() > 150 ? detail.substring(0, 150) : detail));
        });
        table(out, List.of("Node", "Type", "Gate", "Result", "Detail"), gates);
        out.addAll(List.of("", "## 9. Policy guardrails", ""));
        List<List<Object>> findings = new ArrayList<>();
        events.stream().filter(e -> e.get("event").asText().equals("policy.evaluated")).forEach(e -> e.get("data")
                .get("findings").forEach(f -> findings.add(Arrays.asList(e.path("node").asText(), f.get("rule").asText(),
                        f.get("severity").asText(), f.get("path").asText(), f.get("message").asText()))));
        if (findings.isEmpty()) {
            out.add("No policy findings on any change set.");
        } else {
            table(out, List.of("Node", "Rule", "Severity", "Path", "Message"), findings);
        }
        out.addAll(List.of("", "## 10. Human approvals", ""));
        JsonNode approvals = Json.MAPPER.createObjectNode();
        try {
            Path ap = dir.resolve("approvals.json");
            if (Files.exists(ap)) {
                approvals = Json.parse(Files.readString(ap));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (approvals.path("requests").isEmpty()) {
            out.add("None required.");
        } else {
            List<List<Object>> rows = new ArrayList<>();
            JsonNode decisions = approvals.get("decisions");
            approvals.get("requests").fields().forEachRemaining(e -> {
                JsonNode d = decisions.path(e.getKey());
                rows.add(Arrays.asList(e.getValue().get("node").asText(), e.getValue().get("subject").asText(),
                        join(e.getValue().get("reasons"), "<br>"), d.path("status").asText("PENDING"),
                        d.path("by").asText(""), d.path("waited_s").asText("")));
            });
            table(out, List.of("Node", "Subject", "Reasons", "Decision", "By", "Waited (s)"), rows);
        }
        out.addAll(List.of("", "## 11. Decision log (lineage)", ""));
        List<List<Object>> drows = new ArrayList<>();
        for (Decision d : store.decisions) {
            drows.add(Arrays.asList(d.id, d.node, d.decidedBy, d.title, d.rationale, d.inputs));
        }
        table(out, List.of("Id", "Node", "By", "Decision", "Rationale", "Inputs"), drows);
        out.add("");
        for (String name : List.of("test_report", "review_report", "release_readiness")) {
            if (store.has(name)) {
                com.fasterxml.jackson.databind.node.ObjectNode art = store.get(name).deepCopy();
                art.remove("tail");
                String text = Json.pretty(art);
                out.addAll(List.of("### " + name, "", "```json", text.length() > 2500 ? text.substring(0, 2500) : text,
                        "```", ""));
            }
        }
        ContextStore.ArtifactVersion release = store.latest("release") != null ? store.latest("release")
                : store.latest("release_readiness");
        if (release != null) {
            out.addAll(List.of("## 12. Artifact lineage", "", "Provenance of `" + release.name + "`:", ""));
            store.lineage(release.name).forEach(x -> out.add("  ".repeat((int) x.get("depth")) + "- " + x.get("artifact")
                    + " v" + x.get("version") + " ← " + x.get("producer")));
            out.add("");
        }
        out.addAll(List.of("## 13. Reliability metrics", ""));
        List<List<Object>> mrows = new ArrayList<>();
        m.forEach((k, v) -> {
            if (!k.equals("stage_latency_ms")) {
                mrows.add(Arrays.asList(k, v));
            }
        });
        table(out, List.of("Metric", "Value"), mrows);
        String verify = AuditLog.verify(dir.resolve("audit.jsonl"));
        out.addAll(List.of("", "Audit chain: **" + (verify == null ? "verified" : "BROKEN") + "** ("
                + (verify == null ? events.size() + " records, chain intact" : verify) + ").", "",
                "## 14. Change sets", "", "Every applied change (including rolled-back attempts) is kept in `changes/`:", ""));
        try (Stream<Path> diffs = Files.list(dir.resolve("changes"))) {
            diffs.map(p -> p.getFileName().toString()).sorted().forEach(d -> out.add("- `changes/" + d + "`"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.add("");
        Path report = dir.resolve("report.md");
        try {
            Files.writeString(report, String.join("\n", out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return report;
    }
}
