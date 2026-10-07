package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.AuditLog;
import io.agentflow.core.RunLock;
import io.agentflow.engine.Engine;
import io.agentflow.engine.RunState;
import io.agentflow.engine.Scenario;
import io.agentflow.model.Node;
import io.agentflow.model.NodeStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Run ownership across processes: exclusivity, failover after a crash, fencing, and a shared audit chain. */
class OwnershipTest {

    @TempDir
    Path dir;

    static Process child(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), OwnershipChild.class.getName()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    @Test
    void aRunHasOneOwnerAndFailsOverWhenTheOwnerIsKilled() throws Exception {
        Process holder = child("lock", dir.toString());
        String line = new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine();
        assertThat(line).startsWith("locked ");
        String childOwner = line.substring("locked ".length());

        assertThatThrownBy(() -> RunLock.acquire(dir, "second")).isInstanceOf(RunLock.RunBusyException.class)
                .hasMessageContaining(childOwner);

        holder.destroyForcibly(); // SIGKILL: no cleanup runs, owner.json is left behind
        assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(dir.resolve("owner.json"))).contains(childOwner);

        try (RunLock takeover = RunLock.acquire(dir, "failover")) {
            assertThat(takeover.ownerId).isNotEqualTo(childOwner);
            assertThat(Files.readString(dir.resolve("owner.json"))).contains(takeover.ownerId);
            assertThatThrownBy(() -> RunLock.acquire(dir, "third")).isInstanceOf(RunLock.RunBusyException.class);
        }
        assertThat(dir.resolve("owner.json")).doesNotExist();
    }

    @Test
    void aStaleOwnerCannotOverwriteStateAfterATakeover() {
        Path run = Engine.create(Scenario.load("brownfield"), dir, "fenced");
        RunState stale = new RunState(run);
        RunState current = new RunState(run);
        current.fencing = stale.fencing + 1;
        current.save();
        stale.status = "running";
        assertThatThrownBy(stale::save).isInstanceOf(RunState.FencedException.class).hasMessageContaining("taken over");
        assertThat(new RunState(run).fencing).isEqualTo(current.fencing);
    }

    @Test
    void anEngineRecoversARunWhoseOwnerCrashedMidChange() throws Exception {
        Path run = Engine.create(Scenario.load("greenfield"), dir, "crashed");
        // What a crashed owner leaves behind: a node marked RUNNING, a half-applied change with its undo
        // log, and its owner.json. The lock itself died with the process.
        RunState st = new RunState(run);
        Node scaffold = st.graph.get("requirements");
        scaffold.status = NodeStatus.RUNNING;
        long fencingBefore = st.fencing;
        st.save();
        Files.writeString(run.resolve("workspace/HalfWritten.java"), "partial");
        Files.writeString(run.resolve("undo/scaffold.inflight.json"), "{\"HalfWritten.java\": null}");
        Files.writeString(run.resolve("owner.json"), "{\"owner\":\"dead-host:1:deadbeef\"}");
        Files.writeString(run.resolve("STOP"), ""); // take over, recover, then stop before new work

        assertThat(new Engine(run, 1, null, null).execute()).isEqualTo("stopped");

        RunState after = new RunState(run);
        assertThat(run.resolve("workspace/HalfWritten.java")).doesNotExist();
        assertThat(run.resolve("undo/scaffold.inflight.json")).doesNotExist();
        assertThat(after.graph.get("requirements").status).isNotEqualTo(NodeStatus.RUNNING);
        assertThat(after.fencing).isGreaterThan(fencingBefore);
        assertThat(run.resolve("owner.json")).doesNotExist();
        assertThatThrownBy(st::save).isInstanceOf(RunState.FencedException.class);
        List<String> events = AuditLog.read(run.resolve("audit.jsonl")).stream().map(e -> e.get("event").asText())
                .toList();
        assertThat(events).contains("change.rolled_back", "run.session.start");
        assertThat(AuditLog.verify(run.resolve("audit.jsonl"))).isNull();
    }

    @Test
    void concurrentProcessesKeepOneIntactAuditChain() throws Exception {
        Path path = dir.resolve("audit.jsonl");
        Process a = child("audit", path.toString(), "200");
        Process b = child("audit", path.toString(), "200");
        AuditLog log = new AuditLog(path, "run");
        for (int i = 0; i < 200; i++) {
            log.record("parent", null, Map.of("i", i));
        }
        assertThat(a.waitFor(60, TimeUnit.SECONDS) && b.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(a.exitValue()).isZero();
        assertThat(b.exitValue()).isZero();
        List<JsonNode> records = AuditLog.read(path);
        assertThat(records).hasSize(600);
        assertThat(AuditLog.verify(path)).isNull();
    }
}
