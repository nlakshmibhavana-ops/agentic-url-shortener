package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentflow.core.Approvals;
import io.agentflow.core.Approvers;
import io.agentflow.core.AuditLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApproversTest {

    @TempDir
    Path dir;

    @Test
    void onlyTheHolderOfTheTokenIsAuthenticatedAndTheTokenIsNotStored() throws Exception {
        Path file = dir.resolve("approvers.yaml");
        String token = Approvers.add(file, "alice", List.of("change"));
        assertThat(Files.readString(file)).doesNotContain(token);
        assertThat(Approvers.authenticate(file, "alice", token).roles()).containsExactly("change");
        assertThatThrownBy(() -> Approvers.authenticate(file, "alice", token + "x"))
                .isInstanceOf(Approvers.AuthenticationException.class);
        assertThatThrownBy(() -> Approvers.authenticate(file, "mallory", token))
                .isInstanceOf(Approvers.AuthenticationException.class);
        assertThatThrownBy(() -> Approvers.authenticate(file, "alice", ""))
                .isInstanceOf(Approvers.AuthenticationException.class);
    }

    @Test
    void theDemoRegistryAuthenticatesThePublishedDemoToken() {
        Approvers.Approver r = Approvers.authenticate(Path.of("config/approvers.yaml"), "reviewer",
                "demo-approver-token-not-for-production");
        assertThat(r.roles()).contains("change", "data", "release");
    }

    @Test
    void anApproverNeedsTheRequiredRole() {
        assertThat(Approvers.requiredRole("release", List.of())).isEqualTo("release");
        assertThat(Approvers.requiredRole("impl.expiry", List.of("DATA-001: migration"))).isEqualTo("data");
        assertThat(Approvers.requiredRole("impl.x", List.of("CHG-002: protected"))).isEqualTo("change");

        Approvals approvals = new Approvals(dir.resolve("approvals.json"), new AuditLog(dir.resolve("audit.jsonl"), "r"));
        approvals.check("impl.expiry", "digest-1", "s", List.of("DATA-001"), "data", Map.of("tree", "t1"));
        Approvers.Approver changeOnly = new Approvers.Approver("bob", List.of("change"));
        assertThatThrownBy(() -> approvals.decide("impl.expiry", true, changeOnly, ""))
                .hasMessageContaining("lacks the 'data' role");
        assertThatThrownBy(() -> approvals.decide("impl.expiry", true, new Approvers.Approver("agent", List.of("data")), ""))
                .hasMessageContaining("human");
        approvals.decide("impl.expiry", true, new Approvers.Approver("carol", List.of("data")), "ok");
        assertThat(approvals.check("impl.expiry", "digest-1", "s", List.of(), "data", Map.of())).isEqualTo(Approvals.APPROVED);
        // The decision binds the exact outcome digest: a different outcome is a new, pending request.
        assertThat(approvals.check("impl.expiry", "digest-2", "s", List.of(), "data", Map.of())).isEqualTo(Approvals.PENDING);
        assertThat(approvals.all().get("decisions").get("impl.expiry@digest-1").get("by").asText()).isEqualTo("carol");
    }
}
