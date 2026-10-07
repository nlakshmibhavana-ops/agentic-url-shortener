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
    private ObjectNode data;

    public Approvals(Path path, AuditLog audit) {
        this.path = path;
        this.audit = audit;
        reload();
    }

    private void reload() {
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

    private Path lockFile() {
        return path.resolveSibling(path.getFileName() + ".lock");
    }

    private void save() {
        FileLocks.writeAtomically(path, Json.pretty(data));
    }

    /**
     * Returns the decision for this exact subject (an outcome digest), registering a request if new.
     * {@code evidence} lists what the digest binds, so the approver sees what they are approving.
     */
    public String check(String node, String subject, String summary, List<String> reasons, String requiredRole,
            Map<String, Object> evidence) {
        return FileLocks.withLock(lockFile(), () -> {
            reload();
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
                req.put("required_role", requiredRole);
                req.set("evidence", Json.tree(evidence));
                req.put("requested_at", Json.now());
                save();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("subject", subject);
                d.put("reasons", reasons);
                d.put("required_role", requiredRole);
                d.put("evidence", evidence);
                audit.record("approval.requested", node, d);
            }
            return PENDING;
        });
    }

    public List<JsonNode> pending() {
        return FileLocks.withLock(lockFile(), () -> {
            reload();
            List<JsonNode> out = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = data.get("requests").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!data.get("decisions").has(e.getKey())) {
                    out.add(e.getValue());
                }
            }
            return out;
        });
    }

    public JsonNode all() {
        return FileLocks.withLock(lockFile(), () -> {
            reload();
            return data.deepCopy();
        });
    }

    /** Records a decision by an authenticated approver who holds the role the request requires. */
    public JsonNode decide(String node, boolean approve, Approvers.Approver approver, String comment) {
        if (approver == null || RESERVED_ACTORS.contains(approver.name().strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("approvals must come from an authenticated human approver");
        }
        return FileLocks.withLock(lockFile(), () -> {
            reload();
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
            String role = req.path("required_role").asText("change");
            if (!approver.roles().contains(role)) {
                throw new IllegalArgumentException("approver '" + approver.name() + "' lacks the '" + role
                        + "' role required for " + node);
            }
            ObjectNode decision = ((ObjectNode) data.get("decisions")).putObject(key);
            decision.put("status", approve ? APPROVED : REJECTED);
            decision.put("by", approver.name());
            decision.put("role", role);
            decision.put("authenticated", "token");
            decision.put("comment", comment == null ? "" : comment);
            decision.put("decided_at", Json.now());
            decision.put("waited_s", Math.round((Json.now() - req.get("requested_at").asDouble()) * 10) / 10.0);
            save();
            @SuppressWarnings("unchecked")
            Map<String, Object> d = Json.convert(decision, Map.class);
            d.put("subject", req.get("subject").asText());
            audit.record("approval.decided", "human:" + approver.name(), node, null, d);
            return decision;
        });
    }
}
