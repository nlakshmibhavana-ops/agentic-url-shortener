package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Workspace;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.Node;
import io.agentflow.core.Diagnosis;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/** Everything an agent may read. Writes go back through its AgentResult. */
public record AgentContext(Node node, ContextStore store, Workspace workspace, JsonNode scenario, int attempt,
        Path runDir, Lock workspaceLock, LlmClient llm, String feedback, Set<String> baselineFiles,
        List<Attempt> history) {

    /** A previous failed attempt on this node in this session: who made it, what it tried, why it failed. */
    public record Attempt(String agent, Integer candidate, Diagnosis diagnosis) {
    }

    /** The most recent failure's diagnosis, or null on a first attempt. */
    public Diagnosis diagnosis() {
        return history == null || history.isEmpty() ? null : history.get(history.size() - 1).diagnosis();
    }

    public String playbook() {
        return scenario.get("playbook").asText();
    }

    /** Runs build tools on the workspace under the merge-queue lock, so no change lands meanwhile. */
    public <T> T locked(java.util.function.Supplier<T> work) {
        workspaceLock.lock();
        try {
            return work.get();
        } finally {
            workspaceLock.unlock();
        }
    }
}
