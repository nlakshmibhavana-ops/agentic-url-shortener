package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentflow.core.Workspace;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
