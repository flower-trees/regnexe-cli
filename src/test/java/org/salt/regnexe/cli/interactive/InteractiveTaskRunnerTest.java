package org.salt.regnexe.cli.interactive;

import org.junit.Test;
import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.cli.ui.ConfirmChoice;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class InteractiveTaskRunnerTest {
    @Test public void liveInputBelongsToRunningTaskAndCancelledWorkerBlocksAnotherTask() throws Exception {
        try (var runner = new InteractiveTaskRunner()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            runner.start(run -> {
                entered.countDown();
                // Simulate an external transport ignoring thread interruption.
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) {} }
            }, error::set);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(runner.steer("focus on recovery"));
                assertEquals(java.util.List.of("focus on recovery"), runner.current().messages());
                runner.pause();
                assertFalse(runner.steer("too late"));
                assertFalse(runner.start(run -> fail("Must not overlap"), error::set));
                assertFalse(runner.awaitIdle(Duration.ofMillis(50)));
            } finally { release.countDown(); }
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2))); assertNull(error.get());
        }
    }
    @Test public void cancelledToolCannotStartSideEffects() throws Exception {
        try (var runner = new InteractiveTaskRunner()) {
            CountDownLatch ready = new CountDownLatch(1), proceed = new CountDownLatch(1);
            AtomicBoolean touched = new AtomicBoolean(); AtomicReference<Throwable> error = new AtomicReference<>();
            Tool guarded = runner.guard(Tool.builder().name("write").description("write").params("x: String")
                    .func(args -> { touched.set(true); return "ok"; }).build(), null);
            runner.start(run -> {
                ready.countDown();
                while (proceed.getCount() > 0) { try { proceed.await(); } catch (InterruptedException ignored) {} }
                assertThrows(AgentStoppedException.class, () -> guarded.getFunc().apply(java.util.Map.of()));
            }, error::set);
            try { assertTrue(ready.await(2, TimeUnit.SECONDS)); runner.pause(); }
            finally { proceed.countDown(); }
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2))); assertFalse(touched.get()); assertNull(error.get());
        }
    }
    @Test public void approvalUsesInputMailboxAndEscapeReleasesWaitingWorker() throws Exception {
        try (var runner = new InteractiveTaskRunner()) {
            StringWriter text = new StringWriter(); var approvals = new InteractiveApproval(runner, new PrintWriter(text, true));
            AtomicReference<ConfirmChoice> answer = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
            runner.start(run -> answer.set(approvals.ask("run", "bash")), error::set);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!text.toString().contains("always") && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(text.toString().contains("always"));
            runner.pause();
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2))); assertEquals(ConfirmChoice.PAUSE, answer.get()); assertNull(error.get());
        }
    }
    @Test public void steeringCancelsOnlyModelRequestsAndPauseIsIdempotent() throws Exception {
        try (var runner = new InteractiveTaskRunner()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger modelStops = new AtomicInteger(), toolStops = new AtomicInteger();
            runner.start(run -> {
                try (var model = run.onCancel(modelStops::incrementAndGet, true);
                     var tool = run.onCancel(toolStops::incrementAndGet, false)) {
                    entered.countDown(); release.await();
                } catch (Exception e) { throw new RuntimeException(e); }
            }, error -> { throw new AssertionError(error); });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(runner.steer("new instruction"));
                assertEquals(1, modelStops.get()); assertEquals(0, toolStops.get());
                runner.pause(); runner.pause();
                assertEquals(2, modelStops.get()); assertEquals(1, toolStops.get());
            } finally { release.countDown(); }
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2)));
        }
    }

}
