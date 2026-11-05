package org.salt.regnexe.cli.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class SessionExecutionLockTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sameSessionIsBusyButDifferentSessionsCanRun() throws Exception {
        Path dir = temporary.getRoot().toPath();
        String id = UUID.randomUUID().toString();
        try (var first = SessionExecutionLock.acquire(dir, id);
             var other = SessionExecutionLock.acquire(dir, UUID.randomUUID().toString())) {
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(dir, id));
        }
        try (var again = SessionExecutionLock.acquire(dir, id)) { assertNotNull(again); }
    }

    @Test public void otherProcessCannotAcquireAndHardExitReleasesOwnership() throws Exception {
        Path dir = temporary.getRoot().toPath();
        String id = UUID.randomUUID().toString();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Holder.class.getName(), dir.toString(), id)
                .redirectErrorStream(true).start();
        try {
            var ready = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return child.inputReader().readLine(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals("READY", ready.get(10, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(dir, id));
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        }
        // Existing lock files are deliberately retained and must not prevent reacquisition.
        try (var again = SessionExecutionLock.acquire(dir, id)) { assertNotNull(again); }
    }

    public static class Holder {
        public static void main(String[] args) throws Exception {
            try (var lock = SessionExecutionLock.acquire(Path.of(args[0]), args[1])) {
                System.out.println("READY");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
