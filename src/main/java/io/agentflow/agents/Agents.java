package io.agentflow.agents;

import java.util.Map;
import java.util.function.Supplier;

/** Agent registry by name. */
public final class Agents {

    private static final Map<String, Supplier<Agent>> REGISTRY = Map.ofEntries(
            Map.entry("requirements", RequirementsAgent::new),
            Map.entry("requirements.llm", LlmRequirementsAgent::new),
            Map.entry("codebase", CodebaseAnalyst::new),
            Map.entry("design", DesignAgent::new),
            Map.entry("planner", PlannerAgent::new),
            Map.entry("implement", ImplementAgent::new),
            Map.entry("implement.llm", LlmImplementAgent::new),
            Map.entry("test_author", ImplementAgent::new),
            Map.entry("test_runner", TestRunnerAgent::new),
            Map.entry("reviewer", ReviewAgent::new),
            Map.entry("docs", DocsAgent::new),
            Map.entry("release_readiness", ReleaseReadinessAgent::new),
            Map.entry("release", ReleaseAgent::new));

    private Agents() {
    }

    public static Agent create(String name) {
        Supplier<Agent> s = REGISTRY.get(name);
        if (s == null) {
            throw new IllegalArgumentException("unknown agent " + name);
        }
        return s.get();
    }
}
