package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Human approval checkpoints. An approval is bound to the exact subject it was given for (a change
 * set's digest). If an agent later produces something different, a new approval is requested.
 */
public class Approvals {

    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String PENDING = "pending";
    private static final Set<String> RESERVED_ACTORS = Set.of("agent", "orchestrator", "system", "");

    private final Path path;
    private final AuditLog audit;
    private final ObjectNode data;

    public Approvals(Path path, AuditLog audit) {
        this.path = path;
        this.audit = audit;
        try {
            this.data = Files.exists(path) ? (ObjectNode) Json.parse(Files.readString(path))
                    : Json.MAPPER.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!data.has("requests")) {
            data.putObject("requests");
        }
        if (!data.has("decisions")) {
            data.putObject("decisions");
        }
    }

    private synchronized void save() {
        try {
            Files.writeString(path, Json.pretty(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized String check(String node, String subject, String summary, List<String> reasons) {
        String key = node + "@" + subject;
        JsonNode decision = data.get("decisions").get(key);
        if (decision != null) {
            return decision.get("status").asText();
        }
        if (!data.get("requests").has(key)) {
            ObjectNode req = ((ObjectNode) data.get("requests")).putObject(key);
            req.put("node", node);
            req.put("subject", subject);
            req.put("summary", summary);
            req.set("reasons", Json.tree(reasons));
            req.put("requested_at", Json.now());
            save();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("subject", subject);
            d.put("reasons", reasons);
            audit.record("approval.requested", node, d);
        }
        return PENDING;
    }

    public synchronized List<JsonNode> pending() {
        List<JsonNode> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = data.get("requests").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!data.get("decisions").has(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    public JsonNode all() {
        return data;
    }

    public synchronized JsonNode decide(String node, boolean approve, String by, String comment) {
        if (by == null || RESERVED_ACTORS.contains(by.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("approvals must name a human approver");
        }
        String key = null;
        Iterator<Map.Entry<String, JsonNode>> it = data.get("requests").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().get("node").asText().equals(node) && !data.get("decisions").has(e.getKey())) {
                key = e.getKey();
            }
        }
        if (key == null) {
            throw new IllegalStateException("no pending approval request for node '" + node + "'");
        }
        JsonNode req = data.get("requests").get(key);
        ObjectNode decision = ((ObjectNode) data.get("decisions")).putObject(key);
        decision.put("status", approve ? APPROVED : REJECTED);
        decision.put("by", by);
        decision.put("comment", comment == null ? "" : comment);
        decision.put("decided_at", Json.now());
        decision.put("waited_s", Math.round((Json.now() - req.get("requested_at").asDouble()) * 10) / 10.0);
        save();
        Map<String, Object> d = Json.convert(decision, Map.class);
        d.put("subject", req.get("subject").asText());
        audit.record("approval.decided", "human:" + by, node, null, d);
        return decision;
    }
}
