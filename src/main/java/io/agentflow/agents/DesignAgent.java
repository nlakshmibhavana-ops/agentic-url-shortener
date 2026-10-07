package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.Knowledge;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Decision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Architecture and design: components, endpoints and ADRs for the requested capabilities. */
public class DesignAgent implements Agent {

    @Override
    public AgentResult run(AgentContext ctx) {
        JsonNode req = ctx.store().get("requirements");
        JsonNode impact = ctx.store().get("impact_analysis");
        Map<String, Object> components = new LinkedHashMap<>();
        TreeSet<String> endpoints = new TreeSet<>();
        List<Map<String, Object>> adrs = new ArrayList<>();
        AgentResult r = new AgentResult();
        for (JsonNode cap : req.get("capabilities")) {
            String id = cap.get("id").asText();
            JsonNode spec = Knowledge.design(id);
            List<String> comps = new ArrayList<>();
            spec.path("components").forEach(c -> comps.add(c.asText()));
            components.put(id, comps);
            spec.path("endpoints").forEach(e -> endpoints.add(e.asText()));
            JsonNode adr = spec.path("adr");
            if (adr.isArray()) {
                List<String> alternatives = new ArrayList<>();
                adr.get(2).forEach(a -> alternatives.add(a.asText()));
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", String.format("ADR-%02d", adrs.size() + 1));
                a.put("capability", id);
                a.put("title", adr.get(0).asText());
                a.put("rationale", adr.get(1).asText());
                a.put("alternatives", alternatives);
                adrs.add(a);
                r.decisions.add(new Decision(adr.get(0).asText(), adr.get(1).asText(), alternatives));
            }
        }
        Map<String, Object> design = new LinkedHashMap<>();
        design.put("style", "layered Spring Boot service: web -> service -> repository (JDBC) -> Flyway-managed schema");
        design.put("components", components);
        design.put("endpoints", new ArrayList<>(endpoints));
        design.put("adrs", adrs);
        design.put("open_questions", req.get("open_questions"));
        if (!impact.isMissingNode()) {
            design.put("change_surface", impact.get("impacted"));
            design.put("compatibility", "Existing routes and response fields are preserved; new request fields are "
                    + "optional; schema changes are additive Flyway migrations (released migrations are immutable).");
            design.put("risks_from_analysis", impact.get("findings"));
        }
        return r.artifact("design", design);
    }
}
