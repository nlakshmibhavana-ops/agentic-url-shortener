package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentflow.core.ContextStore;
import io.agentflow.core.Gates;
import io.agentflow.core.Json;
import io.agentflow.core.Workspace;
import io.agentflow.model.GateResult;
import io.agentflow.model.Node;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-task build gate passes only on execution evidence that the task's intended tests ran and passed. */
@Tag("scenario")
class BuildGateTest {

    static final String TEST = "src/test/java/com/example/linkly/LinkApiTest.java";

    @TempDir
    Path dir;

    Workspace ws;

    @BeforeEach
    void setUp() {
        ws = new Workspace(dir);
        ws.seed(Path.of("fixtures/linkly"));
    }

    GateResult gate(String... verify) {
        Node n = new Node("impl.t", "task", "implement", "t");
        n.params.put("verify", List.of(verify));
        return Gates.evaluate("build", new Gates.Context(n, new ContextStore(), ws,
                Json.MAPPER.createObjectNode().put("playbook", "linkly"), dir));
    }

    @Test
    void passesWithEvidenceThatTheIntendedTestsRan() {
        GateResult r = gate(TEST);
        assertThat(r.passed()).as(r.detail()).isTrue();
        assertThat(r.detail()).contains("executed: LinkApiTest 3/3");
    }

    @Test
    void failsWhenAnIntendedTestIsMissing() {
        GateResult r = gate(TEST, "src/test/java/com/example/linkly/ExpiryTest.java");
        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("expected tests are missing").contains("ExpiryTest");
    }

    @Test
    void failsWhenTheIntendedTestsAreAllSkipped() throws IOException {
        Path t = dir.resolve(TEST);
        Files.writeString(t, Files.readString(t).replace("@Test", "@org.junit.jupiter.api.Disabled @Test"));
        GateResult r = gate(TEST);
        assertThat(r.passed()).isFalse();
        assertThat(r.detail()).contains("no execution evidence that LinkApiTest ran and passed");
    }
}
