package io.agentflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentflow.core.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One unit of work in the delivery graph. Stage nodes come from the scenario template; task nodes
 * are created and revised by the planner. Public fields keep (de)serialisation of run state trivial.
 */
public class Node {

    public String id;
    public String kind;
    public String agent;
    public String title;
    public List<String> deps = new ArrayList<>();
    public List<String> inputs = new ArrayList<>();
    public List<String> outputs = new ArrayList<>();
    public List<String> entryGates = new ArrayList<>();
    public List<String> exitGates = new ArrayList<>();
    public Map<String, Object> params = new LinkedHashMap<>();
    public List<String> writes = new ArrayList<>();
    public Impact impact = Impact.LOW;
    public int maxAttempts = 2;
    public String fallbackAgent;
    public boolean critical = true;
    public String origin = "scenario";

    public NodeStatus status = NodeStatus.PENDING;
    public int attempts;
    public String fingerprint;
    public Map<String, Integer> produced = new LinkedHashMap<>();
    public Map<String, Integer> consumed = new LinkedHashMap<>();
    public Double startedAt;
    public Double finishedAt;
    public String error;
    public String waitReason;
    public int runs;
    /** A verified change awaiting human approval. */
    public Map<String, Object> pending;

    public Node() {
    }

    public Node(String id, String kind, String agent, String title) {
        this.id = id;
        this.kind = kind;
        this.agent = agent;
        this.title = title;
    }

    /** What the node does, independent of its runtime state; used to decide reuse on re-plan. */
    @JsonIgnore
    public String specHash() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("agent", agent);
        spec.put("params", params);
        spec.put("writes", writes);
        spec.put("exit_gates", exitGates);
        return Json.stableHash(spec);
    }

    @SuppressWarnings("unchecked")
    public List<String> verify() {
        Object v = params.get("verify");
        return v == null ? List.of() : (List<String>) v;
    }

    public boolean join() {
        return Boolean.TRUE.equals(params.get("join"));
    }
}
