package io.agentflow.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an agent proposes. The engine, not the agent, decides whether it is accepted. */
public class AgentResult {

    public final Map<String, Object> artifacts = new LinkedHashMap<>();
    public ChangeSet changeset;
    public final List<Decision> decisions = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public long tokens;

    public AgentResult artifact(String name, Object content) {
        artifacts.put(name, content);
        return this;
    }

    public AgentResult note(String note) {
        notes.add(note);
        return this;
    }
}
