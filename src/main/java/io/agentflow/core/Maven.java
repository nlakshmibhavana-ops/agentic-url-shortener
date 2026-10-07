package io.agentflow.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs build tools (Maven, java) inside a workspace with a timeout; never through a shell. */
public final class Maven {

    public record Result(int code, String output) {
        public boolean ok() {
            return code == 0;
        }

        public String tail(int lines) {
            String[] all = output.strip().split("\n");
            return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
        }
    }

    private Maven() {
    }

    /** Extra Maven flags. Default: quiet, batch, offline (run scripts/prefetch.sh once to warm ~/.m2). */
    public static List<String> baseArgs() {
        String env = System.getenv("AGENTFLOW_MAVEN_ARGS");
        return List.of((env != null ? env : "-q -B -o").trim().split("\\s+"));
    }

    public static String executable() {
        String env = System.getenv("AGENTFLOW_MVN");
        return env != null ? env : "mvn";
    }

    public static Result mvn(Path cwd, long timeoutSeconds, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable());
        cmd.addAll(baseArgs());
        cmd.addAll(args);
        // Builds run generated code and plugins: sandboxed, offline, with a scrubbed environment.
        return run(Sandbox.wrap(cmd, cwd, false), cwd, timeoutSeconds, Map.of());
    }

    public static Result run(List<String> cmd, Path cwd, long timeoutSeconds, Map<String, String> env) {
        Path log = null;
        Process process = null;
        try {
            log = Files.createTempFile("agentflow-", ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            // Never inherit the orchestrator's environment (API keys, tokens): allowlisted variables only.
            pb.environment().clear();
            pb.environment().putAll(Sandbox.environment(env));
            process = pb.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(124, Files.readString(log, StandardCharsets.UTF_8) + "\ntimed out after "
                        + timeoutSeconds + "s");
            }
            // Workspace-relative paths: portable evidence, and no local user paths in reports or the audit trail.
            String output = Files.readString(log, StandardCharsets.UTF_8)
                    .replace(cwd.toAbsolutePath().normalize() + "/", "");
            return new Result(process.exitValue(), output);
        } catch (InterruptedException e) {
            // A safe stop interrupts the agent thread: take the build down with it.
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new Result(130, "interrupted");
        } catch (IOException e) {
            return new Result(127, "could not run " + cmd.getFirst() + ": " + e.getMessage());
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    /** "src/test/java/com/example/FooTest.java" -> "FooTest" (surefire's -Dtest format). */
    public static String testClass(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }
}
