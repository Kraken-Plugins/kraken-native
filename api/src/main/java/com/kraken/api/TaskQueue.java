package com.kraken.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The work {@link KrakenApi#invoke} and friends hand to the poll thread, which plays the part of RuneLite's client
 * thread. The poll loop runs the pending tasks at the start of each poll and the tick-end tasks after a game tick's
 * events, the same two queues RuneLite's {@code ClientThread} keeps.
 *
 * <p>A task that returns false runs again at the next poll. A task that throws is logged and dropped.
 */
final class TaskQueue {

    private static final Logger log = Logger.getLogger("kraken.api");

    private final Object lock = new Object();
    private List<BooleanSupplier> pending = new ArrayList<>();
    private List<Runnable> tickEnd = new ArrayList<>();
    private volatile Thread owner;

    /**
     * Makes a thread the client thread: tasks invoked on it run at once.
     *
     * @param thread the poll thread
     */
    void bind(Thread thread) {
        owner = thread;
    }

    /**
     * Whether the calling thread is the client thread.
     *
     * @return true on the poll thread once {@link #bind} has named it
     */
    boolean isClientThread() {
        return Thread.currentThread() == owner;
    }

    /**
     * Runs a task now if called on the client thread, else at the next poll.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    void invoke(BooleanSupplier task) {
        if (isClientThread()) {
            if (!run(task)) {
                invokeLater(task);
            }
            return;
        }
        invokeLater(task);
    }

    /**
     * Runs a task at the next poll, even when called on the client thread.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    void invokeLater(BooleanSupplier task) {
        synchronized (lock) {
            pending.add(task);
        }
    }

    /**
     * Runs a task after the next game tick's event subscribers.
     *
     * @param task the task
     */
    void invokeAtTickEnd(Runnable task) {
        synchronized (lock) {
            tickEnd.add(task);
        }
    }

    /**
     * Runs the tasks queued before this call, in order. Tasks they queue wait for the next call. Client thread only.
     */
    void runPending() {
        List<BooleanSupplier> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = pending;
            pending = new ArrayList<>();
        }
        List<BooleanSupplier> again = new ArrayList<>();
        for (BooleanSupplier task : batch) {
            if (!run(task)) {
                again.add(task);
            }
        }
        if (!again.isEmpty()) {
            synchronized (lock) {
                pending.addAll(0, again);
            }
        }
    }

    /**
     * Runs the tick-end tasks queued before this call. Client thread only, after a game tick's events.
     */
    void runTickEnd() {
        List<Runnable> batch;
        synchronized (lock) {
            if (tickEnd.isEmpty()) {
                return;
            }
            batch = tickEnd;
            tickEnd = new ArrayList<>();
        }
        for (Runnable task : batch) {
            run(() -> {
                task.run();
                return true;
            });
        }
    }

    private static boolean run(BooleanSupplier task) {
        try {
            return task.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Client thread task " + task + " threw", e);
            return true;
        }
    }
}
