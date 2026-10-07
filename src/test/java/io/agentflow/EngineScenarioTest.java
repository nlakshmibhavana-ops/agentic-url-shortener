package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.core.Approvals;
import io.agentflow.core.AuditLog;
import io.agentflow.core.Json;
import io.agentflow.engine.Engine;
import io.agentflow.engine.RunState;
import io.agentflow.engine.Scenario;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.AgentException;
import io.agentflow.model.Node;
import io.agentflow.model.NodeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end orchestration: each test runs real agents, policies and Maven builds of real projects.
 * Slow by nature (minutes); run unit tests alone with -DexcludedGroups=scenario.
 */
@Tag("scenario")
class EngineScenarioTest {

    @TempDir
    Path runs;

    Path start(String name, Map<String, Object> overrides) {
        ObjectNode sc = Scenario.load(name);
        overrides.forEach((k, v) -> sc.set(k, Json.tree(v)));
        return Engine.create(sc, runs, name + "-test");
    }

    static String execute(Path run, LlmClient llm) {
        return new Engine(run, 4, llm, null).execute();
    }

    /** Plays the human: answers questions and approves (or rejects) until the run settles. */
    static String drive(Path run, Map<String, String> answers, Set<String> reject, LlmClient llm) {
        String status = execute(run, llm);
        for (int i = 0; i < 8 && status.equals("waiting"); i++) {
            RunState st = new RunState(run);
            for (JsonNode q : st.store.get("requirements").path("blocking_open")) {
                new Engine(run, 1, null, null).submitAnswer(q.asText(), answers.get(q.asText()), "tester");
            }
            Approvals approvals = new Approvals(run.resolve("approvals.json"), new AuditLog(run.resolve("audit.jsonl"),
                    st.runId));
            for (JsonNode r : approvals.pending()) {
                approvals.decide(r.get("node").asText(), !reject.contains(r.get("node").asText()), "tester", "");
            }
            status = execute(run, llm);
        }
        return status;
    }

    static String drive(Path run) {
        return drive(run, Map.of(), Set.of(), null);
    }

    static JsonNode metrics(Path run) throws IOException {
        return Json.parse(Files.readString(run.resolve("metrics.json")));
    }

    static Node node(Path run, String id) {
        return new RunState(run).graph.get(id);
    }

    @Test
    void greenfieldDeliversATestedReleasedService() throws IOException {
        Path run = start("greenfield", Map.of());
        assertThat(drive(run)).isEqualTo("succeeded");
        JsonNode m = metrics(run);
        assertThat(m.get("nodes_failed").asInt()).isZero();
        assertThat(m.get("approvals").asInt()).isEqualTo(1); // only the release needs a human
        Path ws = run.resolve("workspace");
        assertThat(Files.readString(ws.resolve("VERSION")).strip()).isEqualTo("1.0.0");
        assertThat(ws.resolve("openapi.json")).exists();
        assertThat(ws.resolve("docs/API.md")).exists();
        JsonNode tests = new RunState(run).store.get("test_report");
        assertThat(tests.get("failed").asInt()).isZero();
        assertThat(tests.get("coverage").asDouble()).isGreaterThanOrEqualTo(90);
        assertThat(AuditLog.verify(run.resolve("audit.jsonl"))).isNull();
        // the implementation branches really ran concurrently
        Map<String, double[]> spans = new HashMap<>();
        for (JsonNode e : AuditLog.read(run.resolve("audit.jsonl"))) {
            String n = e.path("node").asText("");
            if (n.startsWith("impl.") && e.get("event").asText().startsWith("attempt.")) {
                spans.computeIfAbsent(n, k -> new double[] {e.get("ts").asDouble(), 0})[1] = e.get("ts").asDouble();
            }
        }
        List<double[]> windows = new ArrayList<>(spans.values());
        windows.sort((a, b) -> Double.compare(a[0], b[0]));
        boolean overlap = false;
        for (int i = 1; i < windows.size(); i++) {
            overlap |= windows.get(i)[0] < windows.get(i - 1)[1];
        }
        assertThat(overlap).isTrue();
    }

    @Test
    void brownfieldRollsBackADefectiveChangeAndRetries() throws IOException {
        Path run = start("brownfield", Map.of());
        assertThat(drive(run)).isEqualTo("succeeded");
        JsonNode m = metrics(run);
        assertThat(m.get("rollbacks").asInt()).isEqualTo(1);
        assertThat(m.get("retries").asInt()).isEqualTo(1);
        assertThat(m.get("approvals").asInt()).isEqualTo(2);
        assertThat(m.get("mttr_s").isNull()).isFalse();
        assertThat(node(run, "impl.expiry").attempts).isEqualTo(2);
        assertThat(Files.readString(run.resolve("workspace/VERSION")).strip()).isEqualTo("0.5.0");
        assertThat(Files.readString(run.resolve("workspace/src/main/resources/db/migration/V2__add_expires_at.sql")))
                .doesNotContain("UPDATE");
        JsonNode impact = new RunState(run).store.get("impact_analysis");
        assertThat(impact.get("findings")).extracting(f -> f.get("kind").asText())
                .contains("lost-update", "cacheable-redirect", "enumerable-ids");
    }

    @Test
    void ambiguousReplansAfterTheAnswerAndReusesFinishedWork() throws IOException {
        Path run = start("ambiguous", Map.of());
        assertThat(execute(run, null)).isEqualTo("waiting");
        assertThat(node(run, "clarify.Q-PRIVACY").status).isEqualTo(NodeStatus.WAITING);
        assertThat(node(run, "perf.redirect_cache").status).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(drive(run, Map.of("Q-PRIVACY", "hashed"), Set.of(), null)).isEqualTo("succeeded");
        JsonNode m = metrics(run);
        assertThat(m.get("replans").asInt()).isEqualTo(1);
        assertThat(m.get("reused_nodes").asInt()).isEqualTo(2);
        assertThat(node(run, "clarify.Q-PRIVACY").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(new RunState(run).store.get("test_report").get("failed").asInt()).isZero();
    }

    @Test
    void choosingRawIpStorageIsStoppedByThePrivacyGuardrail() {
        Path run = start("ambiguous", Map.of());
        assertThat(drive(run, Map.of("Q-PRIVACY", "raw"), Set.of(), null)).isEqualTo("failed");
        Node raw = node(run, "analytics.unique_visitors_raw");
        assertThat(raw.status).isEqualTo(NodeStatus.FAILED);
        assertThat(raw.error).contains("PII-001");
        assertThat(run.resolve("workspace/src/main/resources/db/migration/V2__client_ip.sql")).doesNotExist();
        assertThat(node(run, "release").status).isEqualTo(NodeStatus.BLOCKED);
    }

    @Test
    void rejectedApprovalRollsBackAndHalts() {
        Path run = start("brownfield", Map.of());
        assertThat(drive(run, Map.of(), Set.of("impl.expiry"), null)).isEqualTo("failed");
        assertThat(node(run, "impl.expiry").error).isEqualTo("rejected by human approver");
        assertThat(run.resolve("workspace/src/main/resources/db/migration/V2__add_expires_at.sql")).doesNotExist();
        assertThat(node(run, "release").status).isEqualTo(NodeStatus.BLOCKED);
    }

    @Test
    void safeStopThenResume() throws IOException {
        Path run = start("brownfield", Map.of());
        Files.writeString(run.resolve("STOP"), "");
        assertThat(execute(run, null)).isEqualTo("stopped");
        assertThat(run.resolve("STOP")).doesNotExist();
        assertThat(drive(run)).isEqualTo("succeeded");
    }

    @Test
    void attemptBudgetTriggersSafeStop() {
        Path run = start("brownfield", Map.of("budgets", Map.of("max_attempts", 3, "max_active_s", 600)));
        assertThat(execute(run, null)).isEqualTo("stopped");
        assertThat(new RunState(run).stopReason).contains("budget");
    }

    @Test
    void crashBetweenApplyAndCommitIsRolledBackOnResume() throws IOException {
        Path run = start("greenfield", Map.of());
        Files.writeString(run.resolve("workspace/HalfWritten.java"), "partial");
        Files.writeString(run.resolve("undo/scaffold.inflight.json"), "{\"HalfWritten.java\": null}");
        new Engine(run, 1, null, null).recoverInflight();
        assertThat(run.resolve("workspace/HalfWritten.java")).doesNotExist();
    }

    /** Every model call fails: the run must still complete through the fallback agents. */
    static class FailingLlm implements LlmClient {
        @Override
        public <T> Result<T> structured(String system, String prompt, Class<T> type) {
            throw new AgentException("simulated provider outage");
        }

        @Override
        public String describe() {
            return "failing";
        }
    }

    @Test
    void llmOutageFallsBackToDeterministicAgents() {
        Path run = start("brownfield", Map.of("provider", "ollama"));
        assertThat(drive(run, Map.of(), Set.of(), new FailingLlm())).isEqualTo("succeeded");
        List<String> attempts = new ArrayList<>();
        for (JsonNode e : AuditLog.read(run.resolve("audit.jsonl"))) {
            if (e.get("event").asText().equals("attempt.start")) {
                attempts.add(e.get("node").asText() + "/" + e.get("data").get("agent").asText());
            }
        }
        assertThat(attempts).contains("requirements/requirements.llm", "requirements/requirements",
                "impl.url_validation/implement.llm", "impl.url_validation/implement");
    }
}
