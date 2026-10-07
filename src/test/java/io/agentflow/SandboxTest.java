package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentflow.core.Sandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What generated code can see when the orchestrator runs it. */
class SandboxTest {

    static final Set<String> ALLOWED = Set.of("PATH", "JAVA_HOME", "MAVEN_HOME", "LANG", "TZ", "HOME", "MAVEN_OPTS",
            "PWD", "SHLVL", "_"); // the last three are set by the shell itself

    @TempDir
    Path ws;

    String run(String script, boolean network) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(Sandbox.wrap(List.of("/bin/sh", "-c", script), ws, network))
                .directory(ws.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(Sandbox.environment(Map.of()));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    @Test
    void theEnvironmentIsAnAllowlist() throws Exception {
        assertThat(Sandbox.environment(Map.of()).keySet()).isSubsetOf(ALLOWED);
        // Whatever the orchestrator holds (API keys, tokens, USER, SSH_AUTH_SOCK...) is absent.
        for (String line : run("env", false).split("\n")) {
            if (line.contains("=")) {
                assertThat(line.substring(0, line.indexOf('='))).isIn(ALLOWED.toArray()).as(line);
            }
        }
    }

    @Test
    void underBubblewrapBuildsHaveNoNetworkNoHomeAndOnlyTheirWorkspace(@TempDir Path elsewhere) throws Exception {
        assumeTrue(Sandbox.mode() == Sandbox.Mode.BWRAP, "bubblewrap not available on this host");
        Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "host file");
        // The user's home holds only the read-only JDK mount (if the JDK lives there), never their files.
        Path homeFile = Path.of(System.getProperty("user.home"), ".agentflow-sandbox-probe");
        Files.writeString(homeFile, "host file");
        Files.writeString(ws.resolve("mine.txt"), "visible");
        String out;
        try {
            out = run("cat /proc/net/dev | tail -n +3 | cut -d: -f1 | tr -d ' '; echo ---;"
                    + " cat " + secret + " " + homeFile + " 2>&1; echo ---; cat mine.txt; echo;"
                    + " touch /usr/x 2>&1 | head -1", false);
        } finally {
            Files.delete(homeFile);
        }
        String[] parts = out.split("---");
        assertThat(parts[0].strip()).isEqualTo("lo"); // only loopback: no route out
        assertThat(parts[1]).doesNotContain("host file").contains("No such file");
        assertThat(parts[2]).contains("visible").contains("Read-only");
    }
}
