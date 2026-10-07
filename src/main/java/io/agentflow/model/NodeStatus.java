package io.agentflow.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** Needs a human: an answer to a question, or an approval. */
    WAITING,
    /** An upstream node failed or was rejected. */
    BLOCKED,
    /** Removed by a re-plan. */
    SKIPPED,
    /** Interrupted by a safe stop; resumable. */
    STOPPED;

    public static final Set<NodeStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, BLOCKED, SKIPPED);

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
