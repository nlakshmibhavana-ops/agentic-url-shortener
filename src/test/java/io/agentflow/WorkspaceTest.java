package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentflow.core.Workspace;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceTest {

    @TempDir
    Path dir;

    Workspace ws;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("A.java"), "int x = 1;\nint y = 2;\n");
        ws = new Workspace(dir);
    }

    @Test
    void applyAndRollbackRestoreTheExactState() {
        ChangeSet cs = new ChangeSet("t", "s", List.of(FileOp.edit("A.java", "int x = 1;\n", "int x = 10;\n"),
                FileOp.write("pkg/New.java", "class New {}\n"), new FileOp("delete", "A.java", null, null, null)), 0);
        Workspace.Applied applied = ws.apply(cs);
        assertThat(ws.exists("A.java")).isFalse();
        assertThat(ws.read("pkg/New.java")).isEqualTo("class New {}\n");
        assertThat(applied.diff()).contains("+class New {}");
        ws.rollback(applied.undo());
        assertThat(ws.read("A.java")).isEqualTo("int x = 1;\nint y = 2;\n");
        assertThat(ws.exists("pkg/New.java")).isFalse();
    }

    @Test
    void editAnchorMustMatchExactlyOnceAndNothingIsWritten() {
        ChangeSet cs = new ChangeSet("t", "s", List.of(FileOp.write("B.java", "b"), FileOp.edit("A.java", "absent", "?")), 0);
        assertThatThrownBy(() -> ws.apply(cs)).hasMessageContaining("matched 0 times");
        assertThat(ws.exists("B.java")).isFalse(); // validated before any write: all-or-nothing
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.java", "/etc/passwd", "a/../../x"})
    void pathsCannotEscapeTheWorkspace(String path) {
        assertThatThrownBy(() -> ws.apply(new ChangeSet("t", "s", List.of(FileOp.write(path, "x")), 0)))
                .hasMessageContaining("escapes");
    }

    @Test
    void symbolicLinksAreNeverFollowed(@TempDir Path outside) throws IOException {
        Files.createSymbolicLink(dir.resolve("link"), outside);
        assertThatThrownBy(() -> ws.apply(new ChangeSet("t", "s", List.of(FileOp.write("link/Evil.java", "x")), 0)))
                .hasMessageContaining("symbolic link");
        assertThat(outside.resolve("Evil.java")).doesNotExist();
        Files.createSymbolicLink(dir.resolve("B.java"), outside.resolve("target.java"));
        assertThatThrownBy(() -> ws.read("B.java")).hasMessageContaining("symbolic link");
        assertThat(ws.files()).doesNotContain("B.java");
    }

    @Test
    void applyRefusesAFileThatChangedSinceThePreview() throws IOException {
        Map<String, String[]> planned = ws.preview(new ChangeSet("t", "s",
                List.of(FileOp.edit("A.java", "int x = 1;\n", "int x = 10;\n")), 0));
        Files.writeString(dir.resolve("A.java"), "int x = 1;\nint y = 3;\n"); // a concurrent writer
        assertThatThrownBy(() -> ws.apply(planned)).hasMessageContaining("expected-hash mismatch");
        assertThat(ws.read("A.java")).isEqualTo("int x = 1;\nint y = 3;\n");
    }

    @Test
    void sizeOperationAndFileTypeLimitsAreEnforced() {
        assertThatThrownBy(() -> ws.apply(new ChangeSet("t", "s", List.of(FileOp.write("Big.java", "x".repeat(1_000_001))), 0)))
                .hasMessageContaining("limit");
        List<FileOp> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(FileOp.write("F" + i + ".java", "x"));
        }
        assertThatThrownBy(() -> ws.apply(new ChangeSet("t", "s", many, 0))).hasMessageContaining("operations");
        assertThatThrownBy(() -> ws.apply(new ChangeSet("t", "s", List.of(FileOp.write("run.sh", "rm -rf /")), 0)))
                .hasMessageContaining("file type not allowed");
        assertThat(ws.files()).containsExactly("A.java");
    }

    @Test
    void aFailedWriteLeavesTheTreeUnchangedAndNoTempFiles() throws IOException {
        Path ro = Files.createDirectory(dir.resolve("ro"));
        ro.toFile().setWritable(false);
        try {
            ChangeSet cs = new ChangeSet("t", "s", List.of(FileOp.edit("A.java", "int x = 1;\n", "int x = 10;\n"),
                    FileOp.write("ro/New.java", "class New {}\n")), 0);
            assertThatThrownBy(() -> ws.apply(cs)).isInstanceOf(RuntimeException.class);
            assertThat(ws.read("A.java")).isEqualTo("int x = 1;\nint y = 2;\n");
            try (Stream<Path> all = Files.walk(dir)) {
                assertThat(all.map(Path::toString)).noneMatch(n -> n.endsWith(".tmp"));
            }
        } finally {
            ro.toFile().setWritable(true);
        }
    }
}
