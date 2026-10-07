package io.agentflow.model;

/** Outcome of one gate. A failing human gate means "ask a person", not "the work is wrong". */
public record GateResult(String gate, boolean passed, String detail, boolean human) {

    public static GateResult of(boolean passed, String detail) {
        return new GateResult("", passed, detail, false);
    }

    public GateResult named(String spec) {
        return new GateResult(spec, passed, detail, human);
    }
}
