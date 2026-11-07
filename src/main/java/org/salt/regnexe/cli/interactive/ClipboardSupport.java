package org.salt.regnexe.cli.interactive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Clipboard writes never block terminal input, execute shell text, or log selected content. */
final class ClipboardSupport implements AutoCloseable {
    record Result(String message, String terminalSequence) {}
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rex-clipboard"); thread.setDaemon(true); return thread;
    });
    private final AtomicReference<Process> active = new AtomicReference<>();
    private final Map<String, String> environment = System.getenv();
    void copy(String text, Consumer<Result> callback) {
        worker.execute(() -> {
            Result result;
            if (text.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                result = new Result("Selection exceeds 1 MiB; select a smaller range", "");
            else if (remote(environment)) result = osc52(text);
            else {
                result = null;
                for (var command : commands(System.getProperty("os.name"), environment)) {
                    if (run(command, text, Duration.ofSeconds(2), active)) {
                        result = new Result("Copied " + text.codePointCount(0, text.length()) + " characters (" + command.get(0) + ")", "");
                        break;
                    }
                }
                if (result == null) result = osc52(text);
            }
            if (!worker.isShutdown()) callback.accept(result);
        });
    }
    static boolean remote(Map<String, String> environment) {
        return environment.containsKey("SSH_CONNECTION") || environment.containsKey("SSH_TTY");
    }
    static List<List<String>> commands(String osName, Map<String, String> environment) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return List.of(List.of("pbcopy"));
        if (os.contains("win") || environment.containsKey("WSL_DISTRO_NAME"))
            return List.of(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::InputEncoding = [System.Text.Encoding]::UTF8; Set-Clipboard -Value ([Console]::In.ReadToEnd())"));
        if (environment.containsKey("WAYLAND_DISPLAY"))
            return List.of(List.of("wl-copy"), List.of("xclip", "-selection", "clipboard"), List.of("xsel", "--clipboard", "--input"));
        return List.of(List.of("xclip", "-selection", "clipboard"), List.of("xsel", "--clipboard", "--input"));
    }
    static Result osc52(String text) {
        return new Result("Copy sent via OSC 52; terminal clipboard permission may be required",
                "\u001b]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\u0007");
    }
    static boolean run(List<String> command, String text, Duration timeout, AtomicReference<Process> active) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            active.set(process);
            Process target = process;
            // Covers a blocked stdin write as well as waiting for process exit.
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
                if (target.isAlive()) stop(target);
            });
            try (var stdin = process.getOutputStream()) { stdin.write(text.getBytes(StandardCharsets.UTF_8)); }
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally {
            if (process != null) { if (process.isAlive()) stop(process); active.compareAndSet(process, null); }
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
    @Override public void close() {
        worker.shutdownNow();
        Process process = active.get(); if (process != null) stop(process);
    }
}
