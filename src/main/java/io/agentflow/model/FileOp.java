package io.agentflow.model;

/** One file operation in a change set: write (content), edit (exact old -> new), or delete. */
public record FileOp(String op, String path, String content, String old, String replacement) {

    public static FileOp write(String path, String content) {
        return new FileOp("write", path, content, null, null);
    }

    public static FileOp edit(String path, String old, String replacement) {
        return new FileOp("edit", path, null, old, replacement);
    }
}
