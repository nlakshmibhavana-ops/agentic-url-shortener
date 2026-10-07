package io.agentflow.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Isolation for everything that executes generated code (Maven builds, tests, the packaged app).
 *
 * <p>Always: a scrubbed environment (an allowlist of variables), so credentials in the orchestrator's
 * environment (API keys, tokens) never reach generated code or build plugins.
 *
 * <p>With bubblewrap (Linux user namespaces): the process sees a read-only system, the JDK and Maven,
 * its own workspace read-write, an empty home, the host Maven repository read-only behind a
 * throwaway local repository (it can read cached artifacts but cannot poison the host cache), no
 * other user files, and, for builds, no network at all.
 *
 * <p>{@code AGENTFLOW_SANDBOX}: {@code auto} (default: bubblewrap when it works, else environment-only,
 * recorded in the audit trail), {@code bwrap} (required: fail closed if unavailable), {@code env}
 * (environment scrubbing only).
 */
public final class Sandbox {

    public enum Mode { BWRAP, ENV_ONLY }

    private static final Path SANDBOX_HOME = Path.of("/home/sandbox");
    private static final Path HOST_REPO = SANDBOX_HOME.resolve(".m2/host-repository");
    private static volatile Mode detected;

    private Sandbox() {
    }

    public static Mode mode() {
        Mode m = detected;
        if (m == null) {
            synchronized (Sandbox.class) {
                if (detected == null) {
                    detected = detect();
                }
                m = detected;
            }
        }
        return m;
    }

    private static Mode detect() {
        String wanted = System.getenv().getOrDefault("AGENTFLOW_SANDBOX", "auto").toLowerCase(Locale.ROOT);
        if (wanted.equals("env") || wanted.equals("off")) {
            return Mode.ENV_ONLY;
        }
        boolean works = bwrapWorks();
        if (!works && wanted.equals("bwrap")) {
            throw new IllegalStateException("AGENTFLOW_SANDBOX=bwrap but bubblewrap is unavailable or user namespaces "
                    + "are disabled on this host; refusing to run generated code unsandboxed");
        }
        return works ? Mode.BWRAP : Mode.ENV_ONLY;
    }

    private static boolean bwrapWorks() {
        try {
            Process p = new ProcessBuilder("bwrap", "--unshare-all", "--ro-bind", "/", "/", "--dev", "/dev", "true")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static String describe() {
        return mode() == Mode.BWRAP ? "bwrap (fs + env + network isolation)" : "env-only (environment scrubbed)";
    }

    static Path javaHome() {
        String env = System.getenv("JAVA_HOME");
        Path p = env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("java.home"));
        return realPath(p);
    }

    /** MAVEN_HOME from the environment, or derived from the mvn executable on PATH. */
    static Path mavenHome() {
        String env = System.getenv("MAVEN_HOME");
        if (env != null && !env.isBlank()) {
            return realPath(Path.of(env));
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path mvn = Path.of(dir, "mvn");
            if (Files.isExecutable(mvn)) {
                return realPath(mvn).getParent().getParent();
            }
        }
        return null;
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** The only variables generated code may see. */
    public static Map<String, String> environment(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        Path jh = javaHome();
        Path mh = mavenHome();
        String path = jh.resolve("bin") + (mh != null ? ":" + mh.resolve("bin") : "") + ":/usr/local/bin:/usr/bin:/bin";
        env.put("PATH", path);
        env.put("JAVA_HOME", jh.toString());
        if (mh != null) {
            env.put("MAVEN_HOME", mh.toString());
        }
        env.put("LANG", "C.UTF-8");
        env.put("TZ", System.getenv().getOrDefault("TZ", "UTC"));
        if (mode() == Mode.BWRAP) {
            env.put("HOME", SANDBOX_HOME.toString());
            // Java reads user.home from the passwd entry, not $HOME: point Maven at the sandboxed home.
            env.put("MAVEN_OPTS", "-Duser.home=" + SANDBOX_HOME + " -Dmaven.repo.local.tail=" + HOST_REPO);
        } else {
            env.put("HOME", System.getProperty("user.home"));
        }
        env.putAll(extra);
        return env;
    }

    /** Wraps a command so it runs isolated; {@code network} keeps the host network (needed to serve the app). */
    public static List<String> wrap(List<String> cmd, Path workspace, boolean network) {
        if (mode() != Mode.BWRAP) {
            return cmd;
        }
        Path ws = realPath(workspace);
        List<String> b = new ArrayList<>(List.of("bwrap", network ? "--unshare-user" : "--unshare-all",
                "--die-with-parent", "--new-session"));
        if (network) {
            b.addAll(List.of("--unshare-ipc", "--unshare-pid", "--unshare-uts", "--unshare-cgroup-try"));
        }
        b.addAll(List.of("--ro-bind", "/usr", "/usr", "--ro-bind", "/etc", "/etc",
                "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64", "--symlink", "usr/bin", "/bin",
                "--symlink", "usr/sbin", "/sbin", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
                "--tmpfs", "/home", "--tmpfs", "/root", "--tmpfs", "/opt", "--tmpfs", "/srv", "--tmpfs", "/mnt"));
        Path jh = javaHome();
        b.addAll(List.of("--ro-bind", jh.toString(), jh.toString()));
        Path mh = mavenHome();
        if (mh != null) {
            b.addAll(List.of("--ro-bind", mh.toString(), mh.toString()));
        }
        Path repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        if (Files.isDirectory(repo)) {
            // The host cache is read-only; Maven writes (locks, metadata) go to an empty local repository
            // on the sandbox's tmpfs, chained in front of it, and vanish on exit: no cache poisoning.
            b.addAll(List.of("--ro-bind", realPath(repo).toString(), HOST_REPO.toString()));
        }
        b.addAll(List.of("--bind", ws.toString(), ws.toString(), "--chdir", ws.toString()));
        b.addAll(cmd);
        return b;
    }
}
