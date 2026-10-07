package io.agentflow.core;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** The tree an agent changes: path confinement, all-or-nothing change sets, and undo logs. */
public class Workspace {

    static final Set<String> IGNORED = Set.of("target", "data", ".mvn-cache", ".idea");

    public static class WorkspaceException extends RuntimeException {
        public WorkspaceException(String message) {
            super(message);
        }
    }

    /** Previous content of every path a change set touched (null = did not exist). */
    public record UndoLog(Map<String, String> before) {
    }

    /** Result of applying: the undo log and a unified diff. */
    public record Applied(UndoLog undo, String diff) {
    }

    public final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Limits on any single change set an agent may apply. */
    static final int MAX_OPS = 100;
    static final long MAX_FILE_BYTES = 1_000_000;
    static final long MAX_TOTAL_BYTES = 5_000_000;
    static final java.util.regex.Pattern ALLOWED_FILES = java.util.regex.Pattern.compile(
            ".*\\.(java|xml|properties|yml|yaml|sql|md|json|html|css|js|txt|conf)$|.*/?(VERSION|CHANGELOG|Dockerfile)$");

    /**
     * Resolves a workspace-relative path. Rejects absolute paths, traversal, and any symbolic link on
     * the way (an agent-created symlink could otherwise redirect a write outside the workspace).
     */
    public Path resolve(String rel) {
        if (rel == null || rel.isEmpty() || Path.of(rel).isAbsolute() || rel.contains("\\")) {
            throw new WorkspaceException("path escapes workspace: " + rel);
        }
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new WorkspaceException("path escapes workspace: " + rel);
        }
        for (Path cur = p; cur != null && !cur.equals(root); cur = cur.getParent()) {
            if (Files.isSymbolicLink(cur)) {
                throw new WorkspaceException("refusing to follow symbolic link: " + root.relativize(cur));
            }
        }
        return p;
    }

    public boolean exists(String rel) {
        return Files.exists(resolve(rel));
    }

    public String read(String rel) {
        try {
            return Files.readString(resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Workspace-relative paths of all files (build output excluded), sorted. */
    public List<String> files() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> Arrays.stream(rel.split("/")).noneMatch(IGNORED::contains))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void seed(Path source) {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                String rel = source.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p) || Arrays.stream(rel.split("/")).anyMatch(IGNORED::contains)) {
                    continue;
                }
                if (Files.isSymbolicLink(p)) {
                    throw new WorkspaceException("refusing to seed a symbolic link: " + rel);
                }
                Path dst = resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Computes {path: [before, after]} without touching disk. Throws on a bad anchor or path. */
    public Map<String, String[]> preview(ChangeSet cs) {
        if (cs.ops().size() > MAX_OPS) {
            throw new WorkspaceException("change set has " + cs.ops().size() + " operations (limit " + MAX_OPS + ")");
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (FileOp op : cs.ops()) {
            Path p = resolve(op.path());
            if (!ALLOWED_FILES.matcher(op.path()).matches()) {
                throw new WorkspaceException("file type not allowed for agents: " + op.path());
            }
            if (Files.isDirectory(p)) {
                throw new WorkspaceException("path is a directory: " + op.path());
            }
            if (!before.containsKey(op.path())) {
                before.put(op.path(), Files.exists(p) ? read(op.path()) : null);
            }
        }
        Map<String, String> current = new LinkedHashMap<>(before);
        for (FileOp op : cs.ops()) {
            String text = current.get(op.path());
            switch (op.op()) {
                case "write" -> current.put(op.path(), op.content() == null ? "" : op.content());
                case "delete" -> current.put(op.path(), null);
                case "edit" -> {
                    if (text == null) {
                        throw new WorkspaceException("edit of missing file " + op.path());
                    }
                    int count = op.old() == null || op.old().isEmpty() ? 0 : countOccurrences(text, op.old());
                    if (count != 1) {
                        throw new WorkspaceException("edit anchor in " + op.path() + " matched " + count
                                + " times (needs exactly 1)");
                    }
                    current.put(op.path(), text.replace(op.old(), op.replacement() == null ? "" : op.replacement()));
                }
                default -> throw new WorkspaceException("unknown op " + op.op());
            }
        }
        long total = 0;
        for (Map.Entry<String, String> e : current.entrySet()) {
            long size = e.getValue() == null ? 0 : e.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_FILE_BYTES) {
                throw new WorkspaceException(e.getKey() + " would be " + size + " bytes (limit " + MAX_FILE_BYTES + ")");
            }
            total += size;
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new WorkspaceException("change set writes " + total + " bytes (limit " + MAX_TOTAL_BYTES + ")");
        }
        Map<String, String[]> planned = new TreeMap<>();
        current.forEach((path, after) -> planned.put(path, new String[] {before.get(path), after}));
        return planned;
    }

    static String hash(String content) {
        return content == null ? "absent" : Json.sha256(content);
    }

    public Applied apply(ChangeSet cs) {
        return apply(preview(cs));
    }

    /**
     * Applies a previewed change. Each file's current content must still hash to what the preview saw
     * (optimistic concurrency); then every new file is written to a temp file next to its target and
     * moved into place atomically. If any step fails, files already changed are restored.
     */
    public Applied apply(Map<String, String[]> planned) {
        for (Map.Entry<String, String[]> e : planned.entrySet()) {
            Path p = resolve(e.getKey());
            String now = Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? read(e.getKey()) : null;
            if (!hash(now).equals(hash(e.getValue()[0]))) {
                throw new WorkspaceException("expected-hash mismatch: " + e.getKey() + " changed since the change was"
                        + " planned");
            }
        }
        Map<String, String> undo = new LinkedHashMap<>();
        planned.forEach((path, ba) -> undo.put(path, ba[0]));
        Map<String, String> after = new LinkedHashMap<>();
        planned.forEach((path, ba) -> after.put(path, ba[1]));
        writeAll(after, undo);
        return new Applied(new UndoLog(undo), unifiedDiff(planned));
    }

    public void rollback(UndoLog undo) {
        writeAll(undo.before(), null);
    }

    /** Two-phase write: stage every file to a temp sibling, then atomically move each into place. */
    private void writeAll(Map<String, String> contents, Map<String, String> restoreOnFailure) {
        Map<Path, Path> staged = new LinkedHashMap<>();
        List<String> committed = new ArrayList<>();
        try {
            for (Map.Entry<String, String> e : contents.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                Path target = resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling("." + target.getFileName() + ".agentflow-"
                        + java.util.UUID.randomUUID().toString().substring(0, 8) + ".tmp");
                Files.writeString(tmp, e.getValue(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.SYNC);
                staged.put(target, tmp);
            }
            for (Map.Entry<String, String> e : contents.entrySet()) {
                Path target = resolve(e.getKey());
                if (e.getValue() == null) {
                    Files.deleteIfExists(target);
                } else {
                    Files.move(staged.get(target), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                committed.add(e.getKey());
            }
        } catch (IOException | RuntimeException e) {
            for (Path tmp : staged.values()) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort: a leftover temp file is harmless and ignored by the build
                }
            }
            if (restoreOnFailure != null && !committed.isEmpty()) {
                Map<String, String> restore = new LinkedHashMap<>();
                committed.forEach(path -> restore.put(path, restoreOnFailure.get(path)));
                writeAll(restore, null);
            }
            throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    public static String unifiedDiff(Map<String, String[]> planned) {
        StringBuilder sb = new StringBuilder();
        planned.forEach((path, ba) -> {
            List<String> a = lines(ba[0]);
            List<String> b = lines(ba[1]);
            Patch<String> patch = DiffUtils.diff(a, b);
            if (!patch.getDeltas().isEmpty()) {
                List<String> diff = UnifiedDiffUtils.generateUnifiedDiff(
                        ba[0] == null ? "/dev/null" : "a/" + path, ba[1] == null ? "/dev/null" : "b/" + path,
                        a, patch, 3);
                diff.forEach(line -> sb.append(line).append('\n'));
            }
        });
        return sb.toString();
    }

    private static List<String> lines(String text) {
        return text == null ? List.of() : new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    }

    public static int[] diffStats(String diff) {
        int added = 0;
        int removed = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }
        return new int[] {added, removed};
    }
}
