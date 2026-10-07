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

    public Path resolve(String rel) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || Path.of(rel).isAbsolute()) {
            throw new WorkspaceException("path escapes workspace: " + rel);
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
            return walk.filter(Files::isRegularFile)
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
        Map<String, String> before = new LinkedHashMap<>();
        for (FileOp op : cs.ops()) {
            Path p = resolve(op.path());
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
        Map<String, String[]> planned = new TreeMap<>();
        current.forEach((path, after) -> planned.put(path, new String[] {before.get(path), after}));
        return planned;
    }

    public Applied apply(ChangeSet cs) {
        Map<String, String[]> planned = preview(cs);
        Map<String, String> undo = new LinkedHashMap<>();
        planned.forEach((path, ba) -> undo.put(path, ba[0]));
        planned.forEach((path, ba) -> writeOrDelete(path, ba[1]));
        return new Applied(new UndoLog(undo), unifiedDiff(planned));
    }

    public void rollback(UndoLog undo) {
        undo.before().forEach(this::writeOrDelete);
    }

    private void writeOrDelete(String rel, String content) {
        Path p = resolve(rel);
        try {
            if (content == null) {
                Files.deleteIfExists(p);
            } else {
                Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
