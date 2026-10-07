package io.agentflow.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A recorded decision with its rationale, alternatives and the inputs it was based on. */
public class Decision {

    public String id = "";
    public String node = "";
    public String title;
    public String rationale;
    public List<String> alternatives = new ArrayList<>();
    public String decidedBy = "agent";
    public double ts;
    public Map<String, Integer> inputs = new LinkedHashMap<>();

    public Decision() {
    }

    public Decision(String title, String rationale, List<String> alternatives) {
        this.title = title;
        this.rationale = rationale;
        this.alternatives = new ArrayList<>(alternatives);
    }

    public Decision by(String who) {
        this.decidedBy = who;
        return this;
    }

    public Decision at(String nodeId) {
        this.node = nodeId;
        return this;
    }
}
