package io.agentflow.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Impact {
    LOW,
    /** Requires human approval before the change is committed. */
    HIGH;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
