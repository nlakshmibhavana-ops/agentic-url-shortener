package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentflow.agents.AgentContext;
import io.agentflow.agents.ImplementAgent;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Diagnosis;
import io.agentflow.core.Json;
import io.agentflow.core.Workspace;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Node;
import io.agentflow.model.NoRepairException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Repairs are chosen from the diagnosed failure, never by attempt number. */
class RepairTest {

    // Real gate output from the brownfield sample run and the live-model run.
    static final String MIGRATION_FAILED = "build: tests failed: Tests run: 1, Failures: 1, Errors: 0, Skipped: 0,"
            + " Time elapsed: 1.555 s <<< FAILURE! -- in com.example.linkly.MigrationTest |"
            + " com.example.linkly.MigrationTest.upgradesAnExistingDatabaseWithLiveRows(Path) -- Time elapsed: 1.524 s"
            + " <<< FAILURE! | expected: null | but was: 2026-11-05T19:11:33.202372-05:00 (java.time.OffsetDateTime)";
    static final String COMPILE_FAILED = "build: compile failed: src/main/java/com/example/linkly/UrlValidator.java:[8,26]"
            + " cannot find symbol | src/main/java/com/example/linkly/UrlValidator.java:[8,56] cannot find symbol";

    @TempDir
    Path dir;

    @Test
    void failuresAreDiagnosedFromTheirEvidence() {
        Diagnosis tests = Diagnosis.of(MIGRATION_FAILED);
        assertThat(tests.phase()).isEqualTo("tests");
        assertThat(tests.failingTests()).containsExactly("MigrationTest.upgradesAnExistingDatabaseWithLiveRows");

        Diagnosis compile = Diagnosis.of(COMPILE_FAILED);
        assertThat(compile.phase()).isEqualTo("compile");
        assertThat(compile.locations()).containsExactly("UrlValidator.java:8");

        Diagnosis policy = Diagnosis.of("policy blocked: DATA-002: edits released migration V1__init.sql");
        assertThat(policy.phase()).isEqualTo("policy");
        assertThat(policy.rules()).containsExactly("DATA-002");

        assertThat(Diagnosis.of("build: tests failed: x | failing tests: [ExpiryTest.a, ExpiryTest.b]").failingTests())
                .containsExactly("ExpiryTest.a", "ExpiryTest.b");
    }

    AgentResult implement(List<AgentContext.Attempt> history) {
        Node n = new Node("impl.expiry", "task", "implement", "expiry");
        n.params.put("task", "impl.expiry");
        AgentContext ctx = new AgentContext(n, new ContextStore(), new Workspace(dir),
                Json.tree(Map.of("title", "t", "playbook", "linkly")), history.size() + 1, dir, new ReentrantLock(), null,
                null, Set.of(), history);
        return new ImplementAgent().run(ctx);
    }

    @Test
    void theFirstAttemptUsesThePrimaryChange() {
        assertThat(implement(List.of()).changeset.candidate()).isZero();
    }

    @Test
    void aDiagnosedFailureSelectsTheReviewedRepairForIt() {
        AgentResult r = implement(List.of(new AgentContext.Attempt("implement", 0, Diagnosis.of(MIGRATION_FAILED))));
        assertThat(r.changeset.candidate()).isEqualTo(1);
        assertThat(r.changeset.summary()).contains("nullable");
        assertThat(r.notes.get(0)).contains("reviewed repair for diagnosed tests failure");
    }

    @Test
    void aFailureNoReviewedRepairAddressesStopsForAHuman() {
        assertThatThrownBy(() -> implement(List.of(new AgentContext.Attempt("implement", 0, Diagnosis.of(COMPILE_FAILED)))))
                .isInstanceOf(NoRepairException.class).hasMessageContaining("compile").hasMessageContaining("human");
        String otherTest = "build: tests failed: x | failing tests: [ExpiryTest.expiredLinksReturn410]";
        assertThatThrownBy(() -> implement(List.of(new AgentContext.Attempt("implement", 0, Diagnosis.of(otherTest)))))
                .isInstanceOf(NoRepairException.class).hasMessageContaining("ExpiryTest");
        // Once the repair itself has been tried, the same diagnosis has nothing left.
        assertThatThrownBy(() -> implement(List.of(
                new AgentContext.Attempt("implement", 0, Diagnosis.of(MIGRATION_FAILED)),
                new AgentContext.Attempt("implement", 1, Diagnosis.of(MIGRATION_FAILED)))))
                .isInstanceOf(NoRepairException.class);
    }

    @Test
    void anotherAgentsFailureDoesNotCountAgainstTheReviewedChange() {
        AgentResult r = implement(List.of(new AgentContext.Attempt("implement.llm", null, Diagnosis.of(COMPILE_FAILED))));
        assertThat(r.changeset.candidate()).isZero();
    }
}
