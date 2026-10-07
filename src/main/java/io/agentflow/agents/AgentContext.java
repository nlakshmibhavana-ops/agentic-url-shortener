package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Workspace;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.Node;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/** Everything an agent may read. Writes go back through its AgentResult. */
public record AgentContext(Node node, ContextStore store, Workspace workspace, JsonNode scenario, int attempt,
        Path runDir, Lock workspaceLock, LlmClient llm, String feedback, Set<String> baselineFiles) {

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
