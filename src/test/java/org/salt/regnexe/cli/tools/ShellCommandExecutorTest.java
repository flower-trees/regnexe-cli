package org.salt.regnexe.cli.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ShellCommandExecutorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String execute(String command, Duration timeout) {
        return ShellCommandExecutor.execute(command, temporary.getRoot().toPath(), timeout,
                200_000, () -> false);
    }

    @Test(timeout = 5000)
    public void capturesUtf8StdoutStderrAndNonzeroExit() {
        String result = execute("printf '你好'; printf 'error' >&2; exit 7", Duration.ofSeconds(2));
        assertTrue(result, result.startsWith("Exit: 7"));
        assertTrue(result, result.contains("你好error"));
    }

    @Test(timeout = 5000)
    public void silentCommandTimesOutBeforeItExits() {
        long started = System.nanoTime();
        String result = execute("sleep 20", Duration.ofMillis(250));
        assertTrue(result, result.startsWith("Error: timed out"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
    }

    @Test(timeout = 5000)
    public void timeoutPreservesPartialOutputAndKillsChild() throws Exception {
        Path pidFile = temporary.getRoot().toPath().resolve("child.pid");
        String result = execute("sleep 20 & child=$!; printf '%s' \"$child\" > child.pid; "
                + "echo started; wait", Duration.ofMillis(400));
        try {
            assertTrue(result, result.startsWith("Error: timed out"));
            assertTrue(result, result.contains("started"));
            assertChildStopped(pidFile);
        } finally {
            killChild(pidFile);
        }
    }

    @Test(timeout = 5000)
    public void continuousOutputDoesNotPreventTimeoutOrExceedCaptureLimit() {
        String result = ShellCommandExecutor.execute("while :; do printf 'abcdefghij'; done",
                temporary.getRoot().toPath(), Duration.ofMillis(250), 100, () -> false);
        assertTrue(result, result.startsWith("Error: timed out"));
        assertTrue(result, result.contains("abcdefghij"));
        assertTrue(result, result.contains("more than 100 chars"));
        assertTrue(result.length() < 500);
    }

    @Test(timeout = 5000)
    public void cancellationStopsRunningChildAndRetainsOutput() throws Exception {
        Path pidFile = temporary.getRoot().toPath().resolve("child.pid");
        String result = ShellCommandExecutor.execute(
                "sleep 20 & child=$!; printf '%s' \"$child\" > child.pid; echo started; wait",
                temporary.getRoot().toPath(), Duration.ofSeconds(20), 200_000,
                () -> Files.exists(pidFile));
        try {
            assertTrue(result, result.startsWith("Error: command cancelled."));
            assertTrue(result, result.contains("started"));
            assertChildStopped(pidFile);
        } finally {
            killChild(pidFile);
        }
    }

    @Test(timeout = 5000)
    public void interruptionStopsChildAndRestoresInterruptFlag() throws Exception {
        Path pidFile = temporary.getRoot().toPath().resolve("child.pid");
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(execute("sleep 20 & child=$!; printf '%s' \"$child\" > child.pid; "
                    + "echo started; wait", Duration.ofSeconds(20)));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("child did not start", Files.exists(pidFile));
            worker.interrupt();
            worker.join(2000);
            assertFalse("worker did not stop", worker.isAlive());
            assertTrue(result.get(), result.get().startsWith("Error: command interrupted."));
            assertTrue("interrupt flag lost", interrupted.get());
            assertChildStopped(pidFile);
        } finally {
            worker.interrupt();
            killChild(pidFile);
            worker.join(1000);
        }
    }

    @Test(timeout = 5000)
    public void childHoldingOutputPipeCannotBlockAfterShellExits() throws Exception {
        Path pidFile = temporary.getRoot().toPath().resolve("child.pid");
        long started = System.nanoTime();
        String result = execute("sleep 20 & child=$!; printf '%s' \"$child\" > child.pid; "
                + "echo started; sleep 0.15", Duration.ofMillis(400));
        try {
            // Some JVMs close/drain the shell pipe as soon as the shell exits; others wait
            // for inherited writers. Both must return promptly and clean tracked children.
            assertTrue(result, result.startsWith("Exit: 0") || result.startsWith("Error: timed out"));
            assertTrue(result, result.contains("started"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
            assertChildStopped(pidFile);
        } finally {
            killChild(pidFile);
        }
    }

    @Test(timeout = 5000)
    public void alreadyCancelledCommandNeverStarts() {
        Path marker = temporary.getRoot().toPath().resolve("started");
        String result = ShellCommandExecutor.execute("touch started", temporary.getRoot().toPath(),
                Duration.ofSeconds(2), 100, () -> true);
        assertTrue(result, result.contains("cancelled"));
        assertFalse(Files.exists(marker));
    }

    private void assertChildStopped(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile));
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse("child still alive: " + pid,
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private void killChild(Path pidFile) throws Exception {
        if (Files.exists(pidFile)) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
