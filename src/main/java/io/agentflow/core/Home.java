package io.agentflow.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the project root (playbooks/, scenarios/, fixtures/) and the runs directory. */
public final class Home {

    private Home() {
    }

    public static Path root() {
        String prop = System.getProperty("agentflow.home", System.getenv("AGENTFLOW_HOME"));
        Path p = prop != null ? Path.of(prop) : Path.of("").toAbsolutePath();
        for (Path cur = p.toAbsolutePath(); cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("playbooks")) && Files.isDirectory(cur.resolve("scenarios"))) {
                return cur;
            }
        }
        throw new IllegalStateException("cannot find the agentflow home (playbooks/ and scenarios/); set AGENTFLOW_HOME");
    }

    public static Path runs() {
        String env = System.getenv("AGENTFLOW_RUNS");
        return env != null ? Path.of(env) : root().resolve("runs");
    }
}
