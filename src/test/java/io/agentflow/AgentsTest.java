package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.agents.AgentContext;
import io.agentflow.agents.LlmRequirementsAgent;
import io.agentflow.agents.PlannerAgent;
import io.agentflow.agents.RequirementsAgent;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Json;
import io.agentflow.core.Workspace;
import io.agentflow.engine.Scenario;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Node;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsTest {

    static JsonNode reqs(String scenario, Map<String, Object> answers) {
        return Json.tree(RequirementsAgent.build(Scenario.load(scenario).get("requirement").asText(), Json.tree(answers),
                "t").requirements());
    }

    static Set<String> caps(JsonNode req) {
        Set<String> out = new TreeSet<>();
        req.get("capabilities").forEach(c -> out.add(c.get("id").asText()));
        return out;
    }

    @Test
    void wellDefinedRequirementHasNoOpenQuestions() {
        JsonNode req = reqs("greenfield", Map.of());
        assertThat(req.get("questions")).isEmpty();
        assertThat(caps(req)).contains("link_creation", "redirect", "analytics", "url_safety", "rate_limiting");
    }

    @Test
    void ticketMentionsAreNotNewCapabilities() {
        assertThat(caps(reqs("brownfield", Map.of()))).containsExactly("click_accuracy", "expiry", "url_safety");
    }

    @Test
    void ambiguousRequirementRaisesQuestionsAndAssumesOnlyNonBlocking() {
        JsonNode req = reqs("ambiguous", Map.of());
        assertThat(req.get("questions")).extracting(q -> q.get("id").asText())
                .containsExactlyInAnyOrder("Q-PRIVACY", "Q-PERF", "Q-SAFETY");
        assertThat(req.get("blocking_open")).extracting(JsonNode::asText).containsExactly("Q-PRIVACY");
        assertThat(req.get("assumptions")).hasSize(2);
        assertThat(caps(req)).contains("redirect_cache", "domain_denylist").doesNotContain("unique_visitors");
    }

    @Test
    void answersChangeCapabilities() {
        assertThat(caps(reqs("ambiguous", Map.of("Q-PRIVACY", Map.of("option", "hashed", "by", "x")))))
                .contains("unique_visitors");
        assertThat(caps(reqs("ambiguous", Map.of("Q-PRIVACY", Map.of("option", "none", "by", "x")))))
                .doesNotContain("unique_visitors");
        assertThatThrownBy(() -> reqs("ambiguous", Map.of("Q-PRIVACY", Map.of("option", "maybe", "by", "x"))))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void plannerSerialisesOverlappingWritesAndGatesOpenQuestions() {
        Map<String, Object> existing = Map.of("modules", List.of("com.example.shortener.service.LinkService"));
        JsonNode impact = Json.tree(Map.of("impacted", Map.of("analytics", existing, "redirect", existing,
                "url_safety", existing), "import_graph", Map.of(), "test_map", Map.of()));
        PlannerAgent.Planned p = PlannerAgent.plan("shortener-v2", reqs("ambiguous", Map.of()), impact, false);
        JsonNode tasks = Json.tree(p.plan().get("tasks"));
        JsonNode denylist = null;
        JsonNode clarify = null;
        for (JsonNode t : tasks) {
            if (t.get("id").asText().equals("safety.domain_denylist")) {
                denylist = t;
            }
            if (t.get("id").asText().equals("clarify.Q-PRIVACY")) {
                clarify = t;
            }
        }
        assertThat(denylist.get("deps")).extracting(JsonNode::asText).contains("perf.redirect_cache");
        assertThat(clarify.get("gate").asText()).isEqualTo("clarified:Q-PRIVACY");
        assertThat(p.decisions()).anyMatch(d -> d.title.startsWith("Serialise"));
        assertThat(Json.tree(p.plan().get("satisfied_by_existing")).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("analytics", "redirect", "url_safety");
    }

    @Test
    void plannerRefusesUncoveredCapabilities() {
        JsonNode req = reqs("greenfield", Map.of());
        ((com.fasterxml.jackson.databind.node.ArrayNode) req.get("capabilities")).add(Json.tree(Map.of("id",
                "click_accuracy", "source", "x", "acceptance_criteria", List.of("x"))));
        assertThatThrownBy(() -> PlannerAgent.plan("shortener", req, com.fasterxml.jackson.databind.node.MissingNode
                .getInstance(), false)).hasMessageContaining("no task covers");
    }

    /** A model that over-extracts: it adds analytics for a click-count bug report. */
    static class ScriptedLlm implements LlmClient {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Result<T> structured(String system, String prompt, Class<T> type) {
            return new Result<>((T) new LlmRequirementsAgent.Extraction(
                    List.of("url_safety", "click_accuracy", "expiry", "analytics"), Map.of(), List.of()), 10);
        }

        @Override
        public String describe() {
            return "scripted";
        }
    }

    @Test
    void modelCapabilitiesFollowTheSameRefinementRules(@TempDir Path dir) {
        ContextStore store = new ContextStore();
        store.put("requirement_text", Scenario.load("brownfield").get("requirement").asText(), "scenario", Map.of());
        AgentContext ctx = new AgentContext(new Node("requirements", "stage", "requirements.llm", "r"), store,
                new Workspace(dir), Json.tree(Map.of("title", "t", "playbook", "linkly")), 1, dir, new ReentrantLock(),
                new ScriptedLlm(), null, Set.of());
        AgentResult r = new LlmRequirementsAgent().run(ctx);
        assertThat(caps(Json.tree(r.artifacts.get("requirements"))))
                .containsExactly("click_accuracy", "expiry", "url_safety");
        assertThat(r.notes).anyMatch(n -> n.contains("dropped [analytics]"));
    }
}
