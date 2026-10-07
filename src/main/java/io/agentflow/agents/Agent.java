package io.agentflow.agents;

import io.agentflow.model.AgentResult;

/** An agent owns one SDLC responsibility and proposes a result; it never writes to the workspace. */
public interface Agent {

    AgentResult run(AgentContext ctx);
}
