package io.agentflow.model;

import io.agentflow.core.Json;
import java.util.List;
import java.util.TreeSet;

public record ChangeSet(String taskId, String summary, List<FileOp> ops, int candidate) {

    public String digest() {
        return Json.stableHash(ops);
    }

    public List<String> paths() {
        TreeSet<String> paths = new TreeSet<>();
        ops.forEach(op -> paths.add(op.path()));
        return List.copyOf(paths);
    }
}
