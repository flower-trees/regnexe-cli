package org.salt.regnexe.cli.interactive;

import org.salt.regnexe.cli.ui.ConfirmChoice;
import java.io.PrintWriter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Only the REPL reads the terminal. A worker requests approval through this mailbox. */
public final class InteractiveApproval {
    private static final ConfirmChoice[] CHOICES = {
            ConfirmChoice.NO, ConfirmChoice.YES, ConfirmChoice.ALWAYS, ConfirmChoice.PAUSE};
    private static final String[] LABELS = {"no", "yes", "always (this session)", "pause"};
    public record Menu(String verb, int selected) {
        public String text() {
            StringBuilder text = new StringBuilder(verb).append("? ");
            for (int i = 0; i < LABELS.length; i++) {
                if (i > 0) text.append("  ");
                text.append(i == selected ? "[" + LABELS[i] + "]" : LABELS[i]);
            }
            return text.toString();
        }
    }
    private static final class Request {
        final String verb;
        final CompletableFuture<ConfirmChoice> reply = new CompletableFuture<>();
        final AtomicInteger selected = new AtomicInteger();
        volatile boolean navigated;
        Request(String verb) { this.verb = verb; }
    }
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final InteractiveTaskRunner runner;
    private final PrintWriter out;
    private final Object viewLock = new Object();
    private volatile Consumer<Menu> view;
    public InteractiveApproval(InteractiveTaskRunner runner, PrintWriter out) { this.runner = runner; this.out = out; }
    public void setView(Consumer<Menu> view) {
        synchronized (viewLock) { this.view = view; refresh(); }
    }
    private void refresh() {
        synchronized (viewLock) {
            Request request = pending.get();
            if (view != null) view.accept(request == null || request.reply.isDone() ? null
                    : new Menu(request.verb, request.selected.get()));
        }
    }
    private boolean complete(Request request, ConfirmChoice choice) {
        boolean completed = request.reply.complete(choice);
        refresh();
        return completed;
    }
    public ConfirmChoice ask(String verb, String key) {
        String label = Character.toUpperCase(verb.charAt(0)) + verb.substring(1);
        Request request = new Request(label);
        if (!pending.compareAndSet(null, request)) throw new IllegalStateException("Another approval is pending");
        var run = runner.current();
        try (AutoCloseable ignored = run == null ? () -> {} : run.onCancel(() -> complete(request, ConfirmChoice.PAUSE), false)) {
            if (!request.reply.isDone()) {
                if (view == null) {
                    out.println(label + "? [y/N/pause/always (this session)]"); out.flush();
                }
                refresh();
            }
            return request.reply.get();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ConfirmChoice.PAUSE; }
        catch (Exception e) { throw new RuntimeException(e); }
        finally { if (pending.compareAndSet(request, null)) refresh(); }
    }
    /** Return false when no approval owns the keys, so the editor can handle the arrow. */
    public boolean move(int delta) {
        Request request = pending.get();
        if (request == null || request.reply.isDone()) return false;
        request.selected.updateAndGet(index -> Math.floorMod(index + delta, CHOICES.length));
        request.navigated = true;
        refresh();
        return true;
    }
    /** Menu navigation must not consume a partially typed user instruction. */
    public boolean acceptSelection(boolean inputEmpty) {
        Request request = pending.get();
        if (request == null || request.reply.isDone() || (!inputEmpty && !request.navigated)) return false;
        return complete(request, CHOICES[request.selected.get()]);
    }
    public boolean answer(String input) {
        Request request = pending.get(); if (request == null || request.reply.isDone()) return false;
        ConfirmChoice choice = switch (input.strip().toLowerCase()) {
            case "y", "yes" -> ConfirmChoice.YES;
            case "n", "no" -> ConfirmChoice.NO;
            case "" -> CHOICES[request.selected.get()];
            case "always", "a" -> ConfirmChoice.ALWAYS;
            case "pause", "p" -> ConfirmChoice.PAUSE;
            default -> null;
        };
        return choice != null && complete(request, choice);
    }
}
