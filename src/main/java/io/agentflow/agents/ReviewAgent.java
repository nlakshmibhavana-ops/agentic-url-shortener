package io.agentflow.agents;

import io.agentflow.core.Maven;
import io.agentflow.core.Policy;
import io.agentflow.model.AgentResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Automated review: Checkstyle over the whole project, the security scan, and leftover latent defects. */
public class ReviewAgent implements Agent {

    @Override
    public AgentResult run(AgentContext ctx) {
        Maven.Result lint = ctx.locked(() -> Maven.mvn(ctx.workspace().root, 600,
                List.of("org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check")));
        List<String> issues = new ArrayList<>();
        for (String line : lint.output().split("\n")) {
            if (line.startsWith("[WARN]") || line.startsWith("[ERROR] /")) {
                String rel = line.replace(ctx.workspace().root + "/", "").replaceFirst("^\\[(WARN|ERROR)] ", "");
                issues.add(rel);
            }
        }
        if (!lint.ok() && issues.isEmpty()) {
            issues.add("checkstyle failed: " + lint.tail(5));
        }
        List<Map<String, Object>> security = new ArrayList<>();
        for (String path : ctx.workspace().files()) {
            if (path.startsWith("src/main/") && path.endsWith(".java")) {
                Policy.scanJava(path, ctx.workspace().read(path)).forEach(f -> security.add(Policy.asMap(f)));
            }
            if (path.matches(".*\\.(java|xml|properties|yml|yaml|md|sql)$")) {
                Policy.scanText(path, List.of(ctx.workspace().read(path).split("\n")))
                        .forEach(f -> security.add(Policy.asMap(f)));
            }
        }
        List<Map<String, String>> leftovers = new ArrayList<>();
        CodebaseAnalyst.analyse(ctx.workspace()).forEach((cls, info) -> info.smells().forEach(s -> {
            Map<String, String> m = new LinkedHashMap<>(s);
            m.put("module", cls);
            leftovers.add(m);
        }));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("lint", issues);
        report.put("security", security);
        report.put("latent_defects", leftovers);
        return new AgentResult().artifact("review_report", report).note(issues.size() + " checkstyle, "
                + security.size() + " security, " + leftovers.size() + " latent defects (reported, non-blocking)");
    }
}
