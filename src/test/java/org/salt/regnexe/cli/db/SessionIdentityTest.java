package org.salt.regnexe.cli.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.regnexe.cli.session.SessionRow;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SessionIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void competingCreatorsReturnSamePersistedIdentityAndWorkspace() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("rex.db");
        try (var a = new RexDatabase(path); var b = new RexDatabase(path)) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var one = pool.submit(() -> { start.await(); return a.findOrCreateSession(candidate("/one")); });
                var two = pool.submit(() -> { start.await(); return b.findOrCreateSession(candidate("/two")); });
                start.countDown();
                var first = one.get(10, TimeUnit.SECONDS);
                var second = two.get(10, TimeUnit.SECONDS);
                assertEquals(first.getSessionId(), second.getSessionId());
                assertEquals(first.getWorkingDir(), second.getWorkingDir());
                assertEquals(first.getSessionId(), a.findSessionByName("same").orElseThrow().getSessionId());
                assertEquals(1, a.listSessions().size());
            } finally { pool.shutdownNow(); }
        }
    }

    private SessionRow candidate(String workspace) {
        SessionRow row = new SessionRow();
        row.setSessionId(UUID.randomUUID().toString()); row.setName("same"); row.setWorkingDir(workspace);
        row.setCreatedAt(1); row.setUpdatedAt(1);
        return row;
    }
}
