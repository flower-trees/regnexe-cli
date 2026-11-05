package org.salt.regnexe.cli.interactive;

import org.salt.regnexe.cli.ui.ConfirmChoice;
import java.io.PrintWriter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Only the REPL reads the terminal. A worker requests approval through this mailbox. */
public final class InteractiveApproval {
    private final AtomicReference<CompletableFuture<ConfirmChoice>> pending = new AtomicReference<>();
    private final InteractiveTaskRunner runner;
    private final PrintWriter out;
    public InteractiveApproval(InteractiveTaskRunner runner, PrintWriter out) { this.runner = runner; this.out = out; }
    public ConfirmChoice ask(String verb, String key) {
        var reply = new CompletableFuture<ConfirmChoice>();
        if (!pending.compareAndSet(null, reply)) throw new IllegalStateException("Another approval is pending");
        var run = runner.current();
        try (AutoCloseable ignored = run == null ? () -> {} : run.onCancel(() -> reply.complete(ConfirmChoice.PAUSE), false)) {
            out.println(Character.toUpperCase(verb.charAt(0)) + verb.substring(1) + "? [y/N/pause/always (this session)]"); out.flush();
            return reply.get();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ConfirmChoice.PAUSE; }
        catch (Exception e) { throw new RuntimeException(e); }
        finally { pending.compareAndSet(reply, null); }
    }
    public boolean answer(String input) {
        var reply = pending.get(); if (reply == null) return false;
        ConfirmChoice choice = switch (input.strip().toLowerCase()) {
            case "y", "yes" -> ConfirmChoice.YES;
            case "n", "no", "" -> ConfirmChoice.NO;
            case "always", "a" -> ConfirmChoice.ALWAYS;
            case "pause", "p" -> ConfirmChoice.PAUSE;
            default -> null;
        };
        if (choice == null) return false;
        return reply.complete(choice);
    }
}
