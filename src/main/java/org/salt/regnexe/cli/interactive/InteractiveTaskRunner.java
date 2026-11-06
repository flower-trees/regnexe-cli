package org.salt.regnexe.cli.interactive;

import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One running task; terminal input never waits for its Future. */
public final class InteractiveTaskRunner implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rex-agent-worker"); thread.setDaemon(true); return thread;
    });
    private volatile Run active;
    private volatile Consumer<Boolean> stateListener = ignored -> {};
    public void setStateListener(Consumer<Boolean> listener) { stateListener = listener; }

    public synchronized boolean start(Consumer<Run> task, Consumer<Throwable> errors) {
        if (active != null) return false;
        Run run = new Run(); active = run;
        stateListener.accept(true);
        worker.execute(() -> {
            try { task.accept(run); } catch (Throwable e) { errors.accept(e); }
            finally { synchronized (this) { active = null; notifyAll(); } Thread.interrupted(); stateListener.accept(false); }
        });
        return true;
    }
    public boolean isRunning() { return active != null; }
    public Run current() { return active; }
    public boolean steer(String text) {
        Run run = active;
        return run != null && run.steer(text);
    }
    public void pause() { Run run = active; if (run != null) run.cancel(); }
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (active != null) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        return true;
    }
    @Override public void close() {
        pause(); worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public Tool guard(Tool tool, Runnable cancelTransport) {
        return Tool.builder().name(tool.getName()).description(tool.getDescription()).params(tool.getParams())
                .parametersSchema(tool.getParametersSchema()).func(args -> {
                    Run run = active;
                    if (run == null) return tool.getFunc().apply(args);
                    run.checkCancelled();
                    Thread caller = Thread.currentThread();
                    try (var ignored = run.onCancel(() -> {
                        caller.interrupt();
                        if (cancelTransport != null) cancelTransport.run();
                    }, false)) {
                        return tool.getFunc().apply(args);
                    } catch (RuntimeException e) { run.checkCancelled(); throw e; }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).build();
    }

    public static final class Run {
        private final List<String> messages = new ArrayList<>();
        private final Map<Runnable, Boolean> cancellations = new HashMap<>();
        private volatile boolean cancelled;
        private boolean accepting = true;
        private int persisted;
        private int observed;
        private TaskExecutionState state;
        public synchronized boolean steer(String text) {
            if (cancelled || !accepting) return false;
            messages.add(text);
            // An in-flight model answer is stale. Cancelling its HTTP call does not replay tools.
            new ArrayList<>(cancellations.entrySet()).forEach(e -> { if (e.getValue()) e.getKey().run(); });
            return true;
        }
        public synchronized List<String> messages() { return List.copyOf(messages); }
        public synchronized int revision() { return messages.size(); }
        public synchronized TaskExecutionState state() { return state; }
        public synchronized void seal() { accepting = false; }
        public synchronized void observed(int revision) { observed = revision; }
        public synchronized boolean finishIfObserved() {
            if (!cancelled && observed < messages.size()) return false;
            accepting = false; return true;
        }
        public boolean isCancelled() { return cancelled; }
        public void checkCancelled() {
            if (cancelled) throw new AgentStoppedException("Task interrupted by user", null);
        }
        public void cancel() {
            List<Runnable> callbacks;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true; accepting = false;
                callbacks = new ArrayList<>(cancellations.keySet());
            }
            callbacks.forEach(Runnable::run);
        }
        public synchronized AutoCloseable onCancel(Runnable action, boolean model) {
            cancellations.put(action, model);
            if (cancelled) action.run();
            return () -> { synchronized (this) { cancellations.remove(action); } };
        }
        /** Called only by the execution thread/model boundary, never by the input thread. */
        public synchronized void persistMessages(TaskExecutionState current) {
            if (current != null) state = current;
            if (state == null || state.getRequest() == null) return;
            while (persisted < messages.size()) {
                state.getRequest().setGoal(state.getRequest().getGoal()
                        + "\n\n[Additional user instruction]: " + messages.get(persisted++));
            }
        }
    }
}
