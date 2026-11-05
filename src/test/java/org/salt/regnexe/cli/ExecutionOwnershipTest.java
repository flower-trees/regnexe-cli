package org.salt.regnexe.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.cli.db.RexDatabase;
import org.salt.regnexe.cli.session.SessionContext;
import org.salt.regnexe.cli.session.SessionExecutionLock;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ExecutionOwnershipTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void interruptedWaiterDoesNotReleaseLiveWorkerLock() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"))) {
            var ctx = new SessionContext(UUID.randomUUID().toString(), "test",
                    new WorkspaceContext(List.of(temporary.getRoot().toPath())));
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var method = CliMain.class.getDeclaredMethod("runAgentTask", Callable.class, SessionContext.class,
                    PrintWriter.class, AtomicBoolean.class, AtomicInteger.class, RexDatabase.class);
            method.setAccessible(true);
            Thread waiter = new Thread(() -> {
                try {
                    method.invoke(new CliMain(), (Callable<AgentResult>) () -> {
                        started.countDown();
                        try { release.await(); return null; } finally { finished.countDown(); }
                    }, ctx, new PrintWriter(new StringWriter()), new AtomicBoolean(), new AtomicInteger(), db);
                    failure.set(new AssertionError("Waiting should have been interrupted"));
                } catch (InvocationTargetException e) {
                    if (!(e.getCause() instanceof InterruptedException)) failure.set(e.getCause());
                } catch (Throwable e) { failure.set(e); }
            });
            waiter.start();
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                waiter.interrupt();
                waiter.join(10000);
                assertFalse(waiter.isAlive());
                assertNull(failure.get());
                assertThrows(IllegalStateException.class,
                        () -> SessionExecutionLock.acquire(db.lockDirectory(), ctx.getSessionId()));
            } finally {
                release.countDown();
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                waiter.join(10000);
            }
        }
    }

    @Test public void busySessionDoesNotEnterTaskCallable() throws Exception {
        try (var db = new RexDatabase(temporary.getRoot().toPath().resolve("rex.db"))) {
            var ctx = new SessionContext(UUID.randomUUID().toString(), "test",
                    new WorkspaceContext(List.of(temporary.getRoot().toPath())));
            var method = CliMain.class.getDeclaredMethod("runAgentTask", Callable.class, SessionContext.class,
                    PrintWriter.class, AtomicBoolean.class, AtomicInteger.class, RexDatabase.class);
            method.setAccessible(true);
            AtomicBoolean entered = new AtomicBoolean();
            try (var held = SessionExecutionLock.acquire(db.lockDirectory(), ctx.getSessionId())) {
                InvocationTargetException error = assertThrows(InvocationTargetException.class,
                        () -> method.invoke(new CliMain(), (Callable<AgentResult>) () -> {
                            entered.set(true); return null;
                        }, ctx, new PrintWriter(new StringWriter()), new AtomicBoolean(), new AtomicInteger(), db));
                assertTrue(error.getCause() instanceof IllegalStateException);
                assertFalse(entered.get());
            }
        }
    }
}
