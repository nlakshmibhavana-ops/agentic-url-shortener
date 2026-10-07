package io.agentflow.model;

/** An agent could not produce a usable result. Retryable; may switch to the fallback agent. */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
