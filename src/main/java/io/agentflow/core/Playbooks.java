package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

/**
 * Playbooks: reviewed change catalogues the deterministic agents draw from. A playbook is the
 * offline stand-in for model-generated code; the policy, gates, approvals, rollback and
 * re-planning around it are identical whichever agent produced a change set.
 */
public final class Playbooks {

    private static final Map<String, JsonNode> CACHE = new ConcurrentHashMap<>();

    private Playbooks() {
    }

    public static Path dir(String name) {
        return Home.root().resolve("playbooks").resolve(name);
    }

    public static JsonNode load(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            try {
                return Json.tree(new Yaml().load(Files.readString(dir(n).resolve("manifest.yaml"))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public static JsonNode task(String name, String taskId) {
        for (JsonNode t : load(name).get("tasks")) {
            if (t.get("id").asText().equals(taskId)) {
                return t;
            }
        }
        throw new IllegalArgumentException("no task " + taskId + " in playbook " + name);
    }

    public static int candidateCount(String name, String taskId) {
        JsonNode c = task(name, taskId).path("candidates");
        return c.isArray() ? c.size() : 1;
    }

    public static ChangeSet changeset(String name, String taskId, int candidate) {
        JsonNode spec = task(name, taskId);
        JsonNode variants = spec.path("candidates");
        JsonNode variant;
        if (variants.isArray()) {
            variant = variants.get(Math.min(candidate, variants.size() - 1));
        } else {
            variant = Json.MAPPER.createObjectNode().put("summary", spec.get("title").asText())
                    .set("ops", spec.get("ops"));
        }
        List<FileOp> ops = new ArrayList<>();
        for (JsonNode raw : flatten(variant.get("ops"))) {
            String content = null;
            if (raw.has("src")) {
                try {
                    content = Files.readString(dir(name).resolve(raw.get("src").asText()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (raw.has("content")) {
                content = raw.get("content").asText();
            }
            ops.add(new FileOp(raw.get("op").asText(), raw.get("path").asText(), content,
                    raw.has("old") ? raw.get("old").asText() : null, raw.has("new") ? raw.get("new").asText() : null));
        }
        return new ChangeSet(taskId, variant.get("summary").asText(), ops, variants.isArray()
                ? Math.min(candidate, variants.size() - 1) : 0);
    }

    /** YAML anchors let one variant reuse another's op list, which nests lists: flatten them. */
    private static List<JsonNode> flatten(JsonNode items) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.isArray()) {
                out.addAll(flatten(item));
            } else {
                out.add(item);
            }
        }
        return out;
    }
}
