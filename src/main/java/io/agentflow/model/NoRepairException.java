package io.agentflow.model;

/** No reviewed repair addresses the diagnosed failure: retrying would only repeat it, so a human must act. */
public class NoRepairException extends AgentException {

    public NoRepairException(String message) {
        super(message);
    }
}
