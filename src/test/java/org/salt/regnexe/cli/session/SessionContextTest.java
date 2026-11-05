package org.salt.regnexe.cli.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class SessionContextTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SessionContext session(String name) {
        return new SessionContext(UUID.randomUUID().toString(), name,
                new WorkspaceContext(List.of(temporary.getRoot().toPath())));
    }
    private Path locks() { return temporary.getRoot().toPath().resolve("locks"); }

    @Test public void idleAndCompletedSessionsStayOwnedUntilClose() throws Exception {
        var context = session("a");
        context.open(locks());
        try {
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(locks(), context.getSessionId()));
            try (var execution = context.retainExecution()) { assertNotNull(execution); }
            context.open(locks()); // /resume the current session must not acquire its lock twice.
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(locks(), context.getSessionId()));
        } finally { context.close(); }
        try (var reopened = SessionExecutionLock.acquire(locks(), context.getSessionId())) { assertNotNull(reopened); }
    }

    @Test public void busyDestinationPreservesCurrentSessionAndSuccessfulSwitchReleasesOld() throws Exception {
        try (var current = session("a"); var destination = session("b")) {
            current.open(locks());
            String previousId = current.getSessionId();
            try (var busy = SessionExecutionLock.acquire(locks(), destination.getSessionId())) {
                assertThrows(IllegalStateException.class, () -> current.switchTo(destination, locks()));
                assertEquals(previousId, current.getSessionId());
                assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(locks(), previousId));
            }
            current.switchTo(destination, locks());
            assertEquals("b", current.getSessionName());
            try (var old = SessionExecutionLock.acquire(locks(), previousId)) { assertNotNull(old); }
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(locks(), current.getSessionId()));
        }
    }

    @Test public void closingWindowWaitsForExistingWorkerBeforeReleasingOwnership() throws Exception {
        var context = session("a");
        context.open(locks());
        var worker = context.retainExecution();
        try {
            context.close();
            assertThrows(IllegalStateException.class, context::retainExecution);
            assertThrows(IllegalStateException.class, () -> SessionExecutionLock.acquire(locks(), context.getSessionId()));
        } finally { worker.close(); }
        worker.close(); // Closing the same worker handle twice must not release another owner.
        try (var next = SessionExecutionLock.acquire(locks(), context.getSessionId())) { assertNotNull(next); }
    }
}
