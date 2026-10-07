package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.AppRunner;
import io.agentflow.core.Json;
import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Documentation generated from the running application and the run's own artifacts, so it cannot
 * drift: the OpenAPI contract (fetched from the packaged JAR), an API reference, the design record
 * and a changelog entry.
 */
public class DocsAgent implements Agent {

    @Override
    public AgentResult run(AgentContext ctx) {
        JsonNode book = Playbooks.load(ctx.playbook());
        String spec = ctx.locked(() -> {
            String err = AppRunner.packageJar(ctx.workspace().root);
            if (err != null) {
                throw new AgentException(err);
            }
            try (AppRunner app = AppRunner.start(ctx.workspace().root, Map.of(), ctx.runDir().resolve("docs-app"))) {
                HttpResponse<String> r = app.request("GET", "/v3/api-docs", null, Map.of());
                if (r.statusCode() != 200) {
                    throw new AgentException("OpenAPI endpoint returned " + r.statusCode());
                }
                return r.body();
            } catch (IOException e) {
                throw new AgentException("could not start the app to read its OpenAPI contract: " + e.getMessage(), e);
            }
        });
        JsonNode openapi = Json.parse(spec);
        JsonNode plan = ctx.store().get("plan");
        JsonNode req = ctx.store().get("requirements");
        List<String> entry = new ArrayList<>(List.of("## [Unreleased] - " + ctx.scenario().get("title").asText(), ""));
        plan.get("tasks").forEach(t -> {
            if (!t.get("agent").asText().equals("none")) {
                entry.add("- " + t.get("title").asText());
            }
        });
        String changelog = "# Changelog\n\n" + String.join("\n", entry) + "\n";
        if (ctx.workspace().exists("CHANGELOG.md")) {
            changelog += "\n" + ctx.workspace().read("CHANGELOG.md").replaceFirst("^# Changelog\\s*", "");
        }
        List<FileOp> ops = new ArrayList<>(List.of(
                FileOp.write("openapi.json", Json.pretty(openapi) + "\n"),
                FileOp.write("docs/API.md", apiMd(openapi)),
                FileOp.write("docs/DESIGN.md", designMd(ctx.store().get("design"), req)),
                FileOp.write("CHANGELOG.md", changelog)));
        if (book.path("docs").has("readme") && !ctx.workspace().exists("README.md")) {
            try {
                ops.add(FileOp.write("README.md", Files.readString(Playbooks.dir(ctx.playbook())
                        .resolve(book.path("docs").get("readme").asText()))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        TreeSet<String> endpoints = new TreeSet<>();
        openapi.get("paths").fields().forEachRemaining(p -> p.getValue().fieldNames()
                .forEachRemaining(m -> endpoints.add(m.toUpperCase() + " " + p.getKey())));
        AgentResult r = new AgentResult().artifact("docs", Map.of("files", ops.stream().map(FileOp::path).toList(),
                "endpoints", new ArrayList<>(endpoints)));
        r.changeset = new ChangeSet(ctx.node().id, "Generate OpenAPI contract, API reference, design record, changelog",
                ops, 0);
        return r;
    }

    static String apiMd(JsonNode spec) {
        StringBuilder sb = new StringBuilder("# " + spec.path("info").path("title").asText("API") + " API\n\n"
                + "Generated from the running application's OpenAPI contract (`openapi.json`).\n\n"
                + "| Method | Path | Operation | Responses |\n|---|---|---|---|\n");
        new java.util.TreeMap<String, JsonNode>(Json.convert(spec.get("paths"), java.util.Map.class)).keySet()
                .forEach(path -> spec.get("paths").get(path).fields().forEachRemaining(op -> {
                    List<String> codes = new ArrayList<>();
                    op.getValue().path("responses").fieldNames().forEachRemaining(codes::add);
                    sb.append("| ").append(op.getKey().toUpperCase()).append(" | `").append(path).append("` | ")
                            .append(op.getValue().path("operationId").asText("")).append(" | ")
                            .append(String.join(", ", new TreeSet<>(codes))).append(" |\n");
                }));
        return sb.toString();
    }

    static String designMd(JsonNode design, JsonNode req) {
        StringBuilder sb = new StringBuilder("# Design record\n\nArchitecture: " + design.get("style").asText()
                + "\n\n## Capabilities\n\n");
        req.get("capabilities").forEach(c -> {
            List<String> crit = new ArrayList<>();
            c.get("acceptance_criteria").forEach(x -> crit.add(x.asText()));
            sb.append("- **").append(c.get("id").asText()).append("**: ").append(String.join("; ", crit)).append('\n');
        });
        sb.append("\n## Decisions (ADRs)\n\n");
        design.get("adrs").forEach(a -> {
            List<String> alts = new ArrayList<>();
            a.get("alternatives").forEach(x -> alts.add(x.asText()));
            sb.append("### ").append(a.get("id").asText()).append(": ").append(a.get("title").asText()).append("\n\n")
                    .append(a.get("rationale").asText()).append("\n\nAlternatives considered: ")
                    .append(String.join("; ", alts)).append("\n\n");
        });
        if (!req.get("assumptions").isEmpty()) {
            sb.append("## Assumptions\n\n");
            req.get("assumptions").forEach(a -> sb.append("- ").append(a.asText()).append('\n'));
            sb.append('\n');
        }
        if (!req.get("out_of_scope").isEmpty()) {
            sb.append("## Out of scope\n\n");
            req.get("out_of_scope").forEach(a -> sb.append("- ").append(a.asText()).append('\n'));
        }
        return sb.toString();
    }
}
