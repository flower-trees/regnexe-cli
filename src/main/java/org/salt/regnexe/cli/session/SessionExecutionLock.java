package org.salt.regnexe.cli.session;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Single-host execution ownership. Never delete lock files: the inode must stay stable. */
public final class SessionExecutionLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SessionExecutionLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SessionExecutionLock acquire(Path directory, String sessionUuid) throws IOException {
        String id = UUID.fromString(sessionUuid).toString();
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("session-" + id + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Session is busy in another executor: " + id);
            return new SessionExecutionLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IllegalStateException("Session is busy in another executor: " + id, e);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
