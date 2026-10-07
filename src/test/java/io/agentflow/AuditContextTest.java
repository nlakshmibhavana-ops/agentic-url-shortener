package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentflow.core.AuditLog;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditContextTest {

    @TempDir
    Path dir;

    @Test
    void auditChainDetectsModificationAndDeletion() throws IOException {
        Path path = dir.resolve("audit.jsonl");
        AuditLog log = new AuditLog(path, "run");
        for (int i = 0; i < 5; i++) {
            log.record("event", null, Map.of("n", i));
        }
        assertThat(AuditLog.verify(path)).isNull();
        List<String> lines = Files.readAllLines(path);
        List<String> tampered = new ArrayList<>(lines);
        tampered.set(2, lines.get(2).replace("\"n\":2", "\"n\":99"));
        Files.write(path, tampered);
        assertThat(AuditLog.verify(path)).contains("modified");
        List<String> deleted = new ArrayList<>(lines);
        deleted.remove(2);
        Files.write(path, deleted);
        assertThat(AuditLog.verify(path)).contains("chain broken");
    }

    @Test
    void auditChainContinuesAcrossSessions() {
        Path path = dir.resolve("audit.jsonl");
        new AuditLog(path, "run").record("a", null, Map.of());
        new AuditLog(path, "run").record("b", null, Map.of());
        assertThat(AuditLog.verify(path)).isNull();
        assertThat(AuditLog.read(path)).hasSize(2);
    }

    @Test
    void contextVersionsOnlyOnChangeAndTracksLineage() {
        ContextStore store = new ContextStore();
        store.put("text", "v1", "scenario", Map.of());
        assertThat(store.put("req", Map.of("a", 1), "requirements", Map.of("text", 1))).isTrue();
        assertThat(store.put("req", Map.of("a", 1), "requirements", Map.of("text", 1))).isFalse();
        store.put("plan", Map.of("t", List.of()), "plan", Map.of("req", 1));
        assertThat(store.lineage("plan")).extracting(m -> m.get("artifact") + "<-" + m.get("producer"))
                .containsExactly("plan<-plan", "req<-requirements", "text<-scenario");
        ContextStore restored = Json.convert(Json.tree(store), ContextStore.class);
        assertThat(restored.get("plan").get("t").isArray()).isTrue();
    }
}
