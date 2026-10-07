package io.agentflow.agents;

import io.agentflow.core.Json;
import io.agentflow.core.Knowledge;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Model-based capability extraction, validated against the known vocabulary. Questions and their
 * options still come from the governed catalogue, so humans always choose from options the
 * downstream playbooks and policies understand.
 */
public class LlmRequirementsAgent implements Agent {

    public record Extraction(List<String> capabilities, Map<String, String> evidence, List<String> ambiguities) {
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        if (ctx.llm() == null) {
            throw new AgentException("no LLM provider configured");
        }
        String text = ctx.store().get("requirement_text").asText();
        Map<String, Object> vocab = new LinkedHashMap<>();
        Knowledge.capabilities().fieldNames().forEachRemaining(id -> vocab.put(id, Knowledge.criteria(id)));
        LlmClient.Result<Extraction> res = ctx.llm().structured(
                "You are a requirements analyst. Map the request onto the capability vocabulary. "
                        + "Only use ids that appear in the vocabulary.",
                "Vocabulary (id -> acceptance criteria):\n" + Json.pretty(vocab) + "\n\nRequest:\n" + text,
                Extraction.class);
        Extraction ex = res.value();
        List<String> proposed = ex.capabilities() == null ? List.of() : ex.capabilities();
        Set<String> unknown = new TreeSet<>(proposed);
        unknown.removeAll(vocab.keySet());
        if (!unknown.isEmpty()) {
            throw new AgentException("model proposed unknown capabilities " + unknown);
        }
        RequirementsAgent.Built built = RequirementsAgent.build(text, ctx.store().get("human_answers"),
                ctx.scenario().get("title").asText());
        Map<String, Object> req = built.requirements();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> caps = (List<Map<String, Object>>) req.get("capabilities");
        Set<String> have = new HashSet<>();
        caps.forEach(c -> have.add((String) c.get("id")));
        Set<String> everything = new HashSet<>(have);
        everything.addAll(proposed);
        // The same refinement rules as the deterministic path apply to model suggestions.
        Set<String> refined = new HashSet<>();
        Knowledge.refines().fields().forEachRemaining(e -> {
            if (everything.contains(e.getKey())) {
                e.getValue().forEach(g -> refined.add(g.asText()));
            }
        });
        List<String> dropped = new ArrayList<>();
        for (String cap : proposed) {
            if (have.contains(cap)) {
                continue;
            }
            if (refined.contains(cap)) {
                dropped.add(cap);
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", cap);
            c.put("source", ex.evidence() == null ? "model" : ex.evidence().getOrDefault(cap, "model"));
            c.put("acceptance_criteria", Knowledge.criteria(cap));
            caps.add(c);
            have.add(cap);
        }
        req.put("model_ambiguities", ex.ambiguities() == null ? List.of() : ex.ambiguities());
        AgentResult r = new AgentResult().artifact("requirements", req);
        r.decisions.addAll(built.decisions());
        r.tokens = res.tokens();
        r.note("model suggested " + new TreeSet<>(proposed));
        if (!dropped.isEmpty()) {
            r.note("dropped " + dropped + ": refined by a more specific capability in the request");
        }
        return r;
    }
}
