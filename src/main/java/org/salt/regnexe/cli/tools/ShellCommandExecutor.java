package org.salt.regnexe.cli.tools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Executes one foreground command; output reads never block the deadline/cancellation loop. */
final class ShellCommandExecutor {
    private static final long POLL_MILLIS = 25;
    private static final long CLEANUP_MILLIS = 500;

    private ShellCommandExecutor() {}

    static String execute(String command, Path directory, Duration timeout, int captureLimit,
                          BooleanSupplier cancellationRequested) {
        OutputCapture output = new OutputCapture(captureLimit);
        Process process = null;
        Thread reader = null;
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        boolean interrupted = false;
        String status;
        // One deadline covers both process execution and output draining.
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (cancellationRequested.getAsBoolean()) return "Error: command cancelled.";
            if (Thread.currentThread().isInterrupted()) return "Error: command interrupted.";
            process = new ProcessBuilder("/bin/sh", "-c", command)
                    .directory(directory.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().close();
            Process running = process;
            CompletableFuture<Void> drained = new CompletableFuture<>();
            reader = new Thread(() -> {
                try (InputStreamReader input = new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.append(buffer, count);
                    drained.complete(null);
                } catch (IOException e) {
                    drained.completeExceptionally(e);
                }
            }, "rex-shell-output");
            reader.setDaemon(true);
            reader.start();

            while (true) {
                process.descendants().forEach(descendants::add);
                if (cancellationRequested.getAsBoolean()) {
                    status = "Error: command cancelled.";
                    break;
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (!process.isAlive() && drained.isDone()) {
                    drained.get();
                    status = "Exit: " + process.exitValue();
                    break;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    status = "Error: timed out after " + timeout.toMillis() / 1000.0 + "s";
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                        TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)));
            }
        } catch (InterruptedException e) {
            interrupted = true;
            status = "Error: command interrupted.";
        } catch (IOException | ExecutionException e) {
            status = "Error: " + e.getMessage();
        } finally {
            // Clear interruption during bounded cleanup, then restore it for the caller.
            interrupted |= Thread.interrupted();
            try {
                if (process != null) {
                    terminate(process, descendants);
                    // Let the reader retain bytes already in the pipe after termination.
                    if (reader != null) {
                        try {
                            reader.join(CLEANUP_MILLIS);
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    process.getOutputStream().close();
                }
            } catch (IOException ignored) {
                // The process may already have closed its pipes.
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        return output.result(status);
    }

    private static void terminate(Process process, Set<ProcessHandle> descendants) {
        process.descendants().forEach(descendants::add);
        // Stop children first so a waiting shell can reap them before it is terminated.
        descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        boolean interrupted = false;
        try {
            if (!process.waitFor(CLEANUP_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(CLEANUP_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class OutputCapture {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        private OutputCapture(int limit) { this.limit = limit; }

        private synchronized void append(char[] buffer, int count) {
            int retained = Math.min(count, limit - text.length());
            text.append(buffer, 0, retained);
            truncated |= retained < count;
            // Continue draining after the cap to avoid blocking the child on a full pipe.
        }

        private synchronized String result(String status) {
            String shown = ToolOutputOverflow.capOrOffload(text.toString(), "bash");
            return status + (shown.isEmpty() ? "" : "\n" + "─".repeat(40) + "\n" + shown)
                    + (truncated ? "\n[... command produced more than " + limit
                    + " chars; even the saved copy was cut off there ...]" : "");
        }
    }
}
