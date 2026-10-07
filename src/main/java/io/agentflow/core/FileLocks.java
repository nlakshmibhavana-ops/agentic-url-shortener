package io.agentflow.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Cross-process mutual exclusion (an OS file lock, released by the kernel if the holder dies) combined
 * with a per-path in-JVM lock, because a JVM may not hold two OS locks on one file. Plus atomic writes.
 */
public final class FileLocks {

    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private FileLocks() {
    }

    public static <T> T withLock(Path lockFile, Supplier<T> work) {
        Path key = lockFile.toAbsolutePath().normalize();
        ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        jvm.lock();
        try (FileChannel ch = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = ch.lock()) {
            return work.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            jvm.unlock();
        }
    }

    public static void withLock(Path lockFile, Runnable work) {
        withLock(lockFile, () -> {
            work.run();
            return null;
        });
    }

    /** Write to a temp sibling, fsync, then rename over the target: readers never see a partial file. */
    public static void writeAtomically(Path target, String content) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
