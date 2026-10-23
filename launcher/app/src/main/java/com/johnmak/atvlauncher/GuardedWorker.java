package com.johnmak.atvlauncher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The single place that schedules background work for a screen: a closed or shut-down executor drops the task
 * (returns false) instead of throwing, and a failing task is reported to {@code onFailure} instead of escaping
 * to the thread's uncaught handler (which would kill the process).
 */
final class GuardedWorker {
    private final ExecutorService executor;
    private final Consumer<Throwable> onFailure;
    private volatile boolean closed;

    GuardedWorker(ExecutorService executor, Consumer<Throwable> onFailure) {
        this.executor = executor;
        this.onFailure = onFailure;
    }

    /** True when the task was accepted; false when the worker is closed or the executor rejected it. */
    boolean submit(Runnable task) {
        if (closed) return false;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) { // boundary: a worker failure must become a UI state, never a process crash
                    onFailure.accept(t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    void close() {
        closed = true;
        executor.shutdownNow();
    }
}
