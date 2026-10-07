package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the release evidence and residual risks into one checklist. */
public class ReleaseReadinessAgent implements Agent {

    @Override
    public AgentResult run(AgentContext ctx) {
        JsonNode tests = ctx.store().get("test_report");
        JsonNode review = ctx.store().get("review_report");
        JsonNode docs = ctx.store().get("docs");
        JsonNode req = ctx.store().get("requirements");
        double covMin = Playbooks.load(ctx.playbook()).path("coverage_min").asDouble(80);
        double cov = tests.path("coverage").asDouble(0);
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "all tests pass", tests.get("ok").asBoolean() && tests.get("failed").asInt() == 0
                && tests.get("errors").asInt() == 0, tests.get("passed") + " passed / " + tests.get("failed") + " failed");
        check(checks, "line coverage >= " + covMin + "%", cov >= covMin, cov + "%");
        check(checks, "checkstyle clean", review.get("lint").isEmpty(), review.get("lint").size() + " issues");
        check(checks, "no security findings", review.get("security").isEmpty(), review.get("security").size() + " findings");
        List<String> files = new ArrayList<>();
        docs.path("files").forEach(f -> files.add(f.asText()));
        check(checks, "docs generated", !files.isEmpty(), String.join(", ", files));
        check(checks, "no blocking questions open", req.get("blocking_open").isEmpty(), req.get("blocking_open").toString());
        List<String> risks = new ArrayList<>();
        review.get("latent_defects").forEach(d -> risks.add(d.get("module").asText().replaceAll(".*\\.", "") + "."
                + d.get("function").asText() + ": " + d.get("detail").asText()));
        req.get("assumptions").forEach(a -> risks.add("assumption: " + a.asText()));
        boolean ready = checks.stream().allMatch(c -> (Boolean) c.get("passed"));
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("checks", checks);
        readiness.put("ready", ready);
        readiness.put("residual_risks", risks);
        return new AgentResult().artifact("release_readiness", readiness)
                .note("ready=" + ready + ", " + risks.size() + " residual risks");
    }

    private static void check(List<Map<String, Object>> checks, String name, boolean passed, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("check", name);
        c.put("passed", passed);
        c.put("detail", detail);
        checks.add(c);
    }
}
