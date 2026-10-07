package io.agentflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.core.Home;
import io.agentflow.core.Json;
import io.agentflow.model.Impact;
import io.agentflow.model.Node;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Scenario loading and the SDLC stage templates. The stage skeleton is fixed per pipeline; tasks
 * are inserted by the planner. Join stages (test, review, docs) depend on every task in the
 * current plan, so they act as synchronisation barriers after the parallel work.
 */
public final class Scenario {

    private Scenario() {
    }

    public static ObjectNode load(String nameOrPath) {
        Path p = Path.of(nameOrPath);
        if (!Files.exists(p)) {
            p = Home.root().resolve("scenarios").resolve(nameOrPath + ".yaml");
        }
        try {
            ObjectNode s = (ObjectNode) Json.tree(new Yaml().load(Files.readString(p)));
            if (!s.has("pipeline")) {
                s.put("pipeline", "greenfield");
            }
            if (!s.has("budgets")) {
                s.set("budgets", Json.tree(Map.of("max_attempts", 80, "max_active_s", 3600)));
            }
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read scenario " + nameOrPath, e);
        }
    }

    private static Node node(String id, String agent, String title, List<String> deps, List<String> inputs,
            List<String> outputs, List<String> exitGates) {
        Node n = new Node(id, "stage", agent, title);
        n.deps = new ArrayList<>(deps);
        n.inputs = new ArrayList<>(inputs);
        n.outputs = new ArrayList<>(outputs);
        n.exitGates = new ArrayList<>(exitGates);
        return n;
    }

    public static List<Node> stageNodes(JsonNode scenario) {
        boolean llm = List.of("claude", "ollama").contains(scenario.path("provider").asText("deterministic"));
        boolean brownfield = scenario.get("pipeline").asText().equals("brownfield");
        List<Node> nodes = new ArrayList<>();
        Node req = node("requirements", llm ? "requirements.llm" : "requirements",
                "Understand and normalise the requirement; surface ambiguity", List.of(),
                List.of("requirement_text", "human_answers"), List.of("requirements"), List.of("requirements_valid"));
        req.fallbackAgent = llm ? "requirements" : null;
        nodes.add(req);
        List<String> designDeps = List.of("requirements");
        List<String> designInputs = List.of("requirements");
        if (brownfield) {
            nodes.add(node("analyze", "codebase", "Analyse the existing codebase: impacted classes, APIs, data, tests",
                    List.of("requirements"), List.of("requirements"), List.of("impact_analysis"), List.of()));
            designDeps = List.of("analyze");
            designInputs = List.of("requirements", "impact_analysis");
        }
        nodes.add(node("design", "design", "Architecture and design decisions (ADRs)", designDeps, designInputs,
                List.of("design"), List.of("design_covers_requirements")));
        List<String> planInputs = new ArrayList<>(designInputs);
        planInputs.add("design");
        nodes.add(node("plan", "planner", "Decompose into a dependency-ordered task graph", List.of("design"), planInputs,
                List.of("plan"), List.of("plan_valid")));
        Node test = node("test", "test_runner", "Run the full test suite with JaCoCo coverage", List.of("plan"), List.of(),
                List.of("test_report"), List.of("tests_pass", "coverage_min"));
        test.params.put("join", true);
        Node review = node("review", "reviewer", "Automated review: Checkstyle, security scan, latent defects",
                List.of("plan"), List.of(), List.of("review_report"), List.of("lint_clean", "security_clean"));
        review.params.put("join", true);
        Node docs = node("docs", "docs", "Generate OpenAPI contract, API reference, design record, changelog",
                List.of("plan"), List.of("requirements", "design", "plan"), List.of("docs"),
                List.of("openapi_valid", "docs_complete"));
        docs.writes = new ArrayList<>(List.of("openapi.json", "docs/*", "CHANGELOG.md", "README.md"));
        docs.params.put("join", true);
        nodes.addAll(List.of(test, review, docs));
        nodes.add(node("readiness", "release_readiness", "Collect release evidence and residual risks",
                List.of("test", "review", "docs"), List.of("test_report", "review_report", "docs", "requirements"),
                List.of("release_readiness"), List.of("readiness")));
        Node release = node("release", "release", "Cut the release (version, notes) after human approval",
                List.of("readiness"), List.of("release_readiness", "plan"), List.of("release"), List.of("smoke"));
        release.writes = new ArrayList<>(List.of("VERSION", "RELEASE_NOTES.md", "pom.xml"));
        release.impact = Impact.HIGH;
        release.maxAttempts = 1;
        nodes.add(release);
        return nodes;
    }

    public static Node taskNode(JsonNode task, int planVersion) {
        String id = task.get("id").asText();
        if (task.get("agent").asText().equals("none")) {
            Node n = new Node(id, "checkpoint", "none", task.get("title").asText());
            n.deps = new ArrayList<>(List.of("plan"));
            n.entryGates = new ArrayList<>(List.of(task.get("gate").asText()));
            n.origin = "plan:v" + planVersion;
            return n;
        }
        Node n = new Node(id, "task", task.get("agent").asText(), task.get("title").asText());
        n.deps = new ArrayList<>(List.of("plan"));
        task.get("deps").forEach(d -> n.deps.add(d.asText()));
        n.exitGates = new ArrayList<>(List.of("build"));
        List<String> verify = new ArrayList<>();
        task.get("verify").forEach(v -> verify.add(v.asText()));
        n.params.put("task", id);
        n.params.put("verify", verify);
        n.params.put("candidates", task.get("candidates").asInt());
        task.get("writes").forEach(w -> n.writes.add(w.asText()));
        n.impact = task.get("impact").asText().equals("high") ? Impact.HIGH : Impact.LOW;
        n.fallbackAgent = task.get("agent").asText().equals("implement.llm") ? "implement" : null;
        // Room for every reviewed candidate (diagnosis-driven repairs), plus the model's attempt.
        n.maxAttempts = n.fallbackAgent != null ? Math.max(1, task.get("candidates").asInt()) + 1
                : Math.max(2, task.get("candidates").asInt());
        n.origin = "plan:v" + planVersion;
        return n;
    }
}
