package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.model.Decision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-stage context: every value that crosses a stage boundary is a versioned artifact that
 * records its producer and the input versions it was derived from, plus the decision log.
 */
public class ContextStore {

    public static class ArtifactVersion {
        public String name;
        public int version;
        public String digest;
        public JsonNode content;
        public String producer;
        public Map<String, Integer> inputs = new LinkedHashMap<>();
        public double ts;
    }

    public Map<String, List<ArtifactVersion>> artifacts = new LinkedHashMap<>();
    public List<Decision> decisions = new ArrayList<>();

    /** Stores a new version; identical content is not re-versioned. Returns whether it changed. */
    public synchronized boolean put(String name, Object content, String producer, Map<String, Integer> inputs) {
        JsonNode tree = Json.tree(content);
        String digest = Json.stableHash(tree);
        List<ArtifactVersion> history = artifacts.computeIfAbsent(name, k -> new ArrayList<>());
        if (!history.isEmpty() && history.getLast().digest.equals(digest)) {
            return false;
        }
        ArtifactVersion v = new ArtifactVersion();
        v.name = name;
        v.version = history.size() + 1;
        v.digest = digest;
        v.content = tree;
        v.producer = producer;
        v.inputs = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        v.ts = Json.now();
        history.add(v);
        return true;
    }

    public synchronized boolean has(String name) {
        List<ArtifactVersion> h = artifacts.get(name);
        return h != null && !h.isEmpty();
    }

    /** Latest content, or a MissingNode when absent (so .path(...) chains stay safe). */
    public synchronized JsonNode get(String name) {
        List<ArtifactVersion> h = artifacts.get(name);
        return h == null || h.isEmpty() ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : h.getLast().content;
    }

    public synchronized ArtifactVersion latest(String name) {
        List<ArtifactVersion> h = artifacts.get(name);
        return h == null || h.isEmpty() ? null : h.getLast();
    }

    public synchronized int version(String name) {
        ArtifactVersion v = latest(name);
        return v == null ? 0 : v.version;
    }

    /** Drops the newest version (used to unstage an attempt whose output was rejected). */
    public synchronized void dropLatest(String name) {
        List<ArtifactVersion> h = artifacts.get(name);
        if (h != null && !h.isEmpty()) {
            h.removeLast();
        }
    }

    /** Provenance of an artifact, walked back to its roots: (artifact, version, producer, depth). */
    public synchronized List<Map<String, Object>> lineage(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        ArtifactVersion latest = latest(name);
        if (latest != null) {
            walk(name, latest.version, 0, new HashSet<>(), out);
        }
        return out;
    }

    private void walk(String name, int version, int depth, Set<String> seen, List<Map<String, Object>> out) {
        if (!artifacts.containsKey(name) || !seen.add(name + "@" + version)) {
            return;
        }
        ArtifactVersion av = artifacts.get(name).get(version - 1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("artifact", name);
        row.put("version", version);
        row.put("producer", av.producer);
        row.put("depth", depth);
        out.add(row);
        new TreeMap<>(av.inputs).forEach((parent, v) -> walk(parent, v, depth + 1, seen, out));
    }

    public synchronized Decision addDecision(Decision d) {
        if (d.id == null || d.id.isEmpty()) {
            d.id = String.format("D%03d", decisions.size() + 1);
        }
        if (d.ts == 0) {
            d.ts = Json.now();
        }
        decisions.add(d);
        return d;
    }
}
