package io.agentflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Graph;
import io.agentflow.core.Json;
import io.agentflow.core.Workspace;
import io.agentflow.model.Node;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Everything needed to resume a run, persisted atomically as runs/&lt;id&gt;/state.json. */
public class RunState {

    /** The serialised form. */
    public static class File {
        public String runId;
        public ObjectNode scenario;
        public String status = "created";
        public String stopReason;
        public List<String> baseline = new ArrayList<>();
        public double createdAt;
        public List<Node> nodes = new ArrayList<>();
        public ContextStore context = new ContextStore();
    }

    public final Path dir;
    public final String runId;
    public final ObjectNode scenario;
    public String status;
    public String stopReason;
    public final Set<String> baseline;
    public final double createdAt;
    public final Graph graph;
    public final ContextStore store;

    public RunState(Path dir) {
        this.dir = dir;
        File f;
        try {
            f = Json.MAPPER.readValue(Files.readString(dir.resolve("state.json")), File.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.runId = f.runId;
        this.scenario = f.scenario;
        this.status = f.status;
        this.stopReason = f.stopReason;
        this.baseline = new TreeSet<>(f.baseline);
        this.createdAt = f.createdAt;
        this.graph = new Graph(f.nodes);
        this.store = f.context;
    }

    public Workspace workspace() {
        return new Workspace(dir.resolve("workspace"));
    }

    public synchronized void save() {
        File f = new File();
        f.runId = runId;
        f.scenario = scenario;
        f.status = status;
        f.stopReason = stopReason;
        f.baseline = new ArrayList<>(baseline);
        f.createdAt = createdAt;
        graph.topologicalOrder().forEach(id -> f.nodes.add(graph.get(id)));
        f.context = store;
        write(dir, f);
    }

    static void write(Path dir, File f) {
        try {
            Path tmp = dir.resolve("state.json.tmp");
            synchronized (f.context) {
                Files.writeString(tmp, Json.pretty(f));
            }
            // Atomic rename: a crash never leaves half a state file.
            Files.move(tmp, dir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode scenarioValue(String key) {
        return scenario.path(key);
    }
}
