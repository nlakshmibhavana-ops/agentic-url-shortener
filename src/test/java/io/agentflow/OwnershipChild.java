package io.agentflow;

import io.agentflow.core.AuditLog;
import io.agentflow.core.RunLock;
import java.nio.file.Path;
import java.util.Map;

/** A separate JVM for the ownership tests: holds a run lock until killed, or appends audit records. */
public final class OwnershipChild {

    private OwnershipChild() {
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("lock")) {
            RunLock lock = RunLock.acquire(Path.of(args[1]), "child");
            System.out.println("locked " + lock.ownerId);
            System.out.flush();
            Thread.sleep(600_000); // killed by the test
        } else {
            AuditLog log = new AuditLog(Path.of(args[1]), "run");
            for (int i = 0; i < Integer.parseInt(args[2]); i++) {
                log.record("child", null, Map.of("i", i));
            }
        }
    }
}
