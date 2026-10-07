package io.agentflow.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Durable, exclusive ownership of a run. Only the holder may execute it or change its state. The OS
 * releases the lock if the holder crashes, so another process can take over (failover) without manual
 * cleanup; owner.json records who holds it, for diagnostics.
 */
public final class RunLock implements AutoCloseable {

    public static class RunBusyException extends RuntimeException {
        public RunBusyException(String message) {
            super(message);
        }
    }

    public final String ownerId;
    private final Path dir;
    private final FileChannel channel;
    private final FileLock lock;

    private RunLock(Path dir, String ownerId, FileChannel channel, FileLock lock) {
        this.dir = dir;
        this.ownerId = ownerId;
        this.channel = channel;
        this.lock = lock;
    }

    public static RunLock acquire(Path runDir, String purpose) {
        FileChannel ch = null;
        try {
            ch = FileChannel.open(runDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held by this JVM
            }
            if (lock == null) {
                ch.close();
                Path owner = runDir.resolve("owner.json");
                String who = Files.exists(owner) ? Files.readString(owner).strip() : "another process";
                throw new RunBusyException("run " + runDir.getFileName() + " is owned by " + who);
            }
            String id = host() + ":" + ManagementFactory.getRuntimeMXBean().getPid() + ":"
                    + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("owner", id);
            owner.put("purpose", purpose);
            owner.put("since", Json.now());
            FileLocks.writeAtomically(runDir.resolve("owner.json"), Json.write(owner));
            return new RunLock(runDir, id, ch, lock);
        } catch (IOException e) {
            try {
                if (ch != null) {
                    ch.close();
                }
            } catch (IOException ignored) {
                // nothing more to release
            }
            throw new UncheckedIOException(e);
        }
    }

    /** A stable host fingerprint: distinguishes owners across hosts without publishing the hostname. */
    private static String host() {
        try {
            return "host-" + Json.sha256(InetAddress.getLocalHost().getHostName()).substring(0, 8);
        } catch (IOException e) {
            return "unknown-host";
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(dir.resolve("owner.json"));
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Simulates the holder dying: the lock is released but owner.json is left behind, as after a crash. */
    void abandon() {
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
