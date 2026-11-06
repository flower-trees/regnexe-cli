package org.salt.regnexe.cli.interactive;

import org.junit.Test;
import org.salt.regnexe.cli.ui.ConfirmChoice;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class InteractiveApprovalTest {
    @Test public void enterDefaultsToNoAndArrowMovementWraps() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ask();
            assertEquals(0, fixture.menu.get().selected());
            assertEquals("", fixture.output.toString());
            assertTrue(fixture.approvals.acceptSelection(true));
            fixture.finished(ConfirmChoice.NO);
            fixture.ask();
            assertTrue(fixture.approvals.move(-1)); assertEquals(3, fixture.menu.get().selected());
            assertTrue(fixture.approvals.move(1)); assertEquals(0, fixture.menu.get().selected());
            fixture.approvals.move(1); fixture.approvals.move(1);
            assertTrue(fixture.menu.get().text().contains("[always (this session)]"));
            assertTrue(fixture.approvals.acceptSelection(true)); fixture.finished(ConfirmChoice.ALWAYS);
            assertFalse(fixture.approvals.move(1));
        }
    }
    @Test public void navigatedSelectionCanBeAcceptedWithoutConsumingAnInputDraft() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ask();
            assertFalse(fixture.approvals.acceptSelection(false));
            assertFalse(fixture.approvals.answer("keep this instruction"));
            fixture.approvals.move(1);
            assertTrue(fixture.approvals.acceptSelection(false)); fixture.finished(ConfirmChoice.YES);
        }
    }
    @Test public void cancellationHidesMenuAndNextApprovalStartsAtNo() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ask(); fixture.approvals.move(2); fixture.runner.pause();
            fixture.finished(ConfirmChoice.PAUSE); assertNull(fixture.menu.get());
            fixture.ask(); assertEquals(0, fixture.menu.get().selected());
            assertTrue(fixture.approvals.answer("yes")); fixture.finished(ConfirmChoice.YES);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractiveTaskRunner runner = new InteractiveTaskRunner();
        final StringWriter output = new StringWriter();
        final InteractiveApproval approvals = new InteractiveApproval(runner, new PrintWriter(output, true));
        final AtomicReference<InteractiveApproval.Menu> menu = new AtomicReference<>();
        final AtomicReference<ConfirmChoice> answer = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile CountDownLatch shown;
        Fixture() {
            approvals.setView(value -> { menu.set(value); if (value != null) shown.countDown(); });
        }
        void ask() throws Exception {
            shown = new CountDownLatch(1); answer.set(null); error.set(null);
            assertTrue(runner.start(run -> answer.set(approvals.ask("run", "bash")), error::set));
            assertTrue(shown.await(2, TimeUnit.SECONDS));
        }
        void finished(ConfirmChoice expected) throws Exception {
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2))); assertNull(error.get()); assertEquals(expected, answer.get());
        }
        @Override public void close() { runner.close(); }
    }
}
