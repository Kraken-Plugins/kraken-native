package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;
import com.kraken.client.ProcessAttacher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs code on the client thread. Kraken's client thread is the API's poll thread ({@code kraken-api-poll}): game
 * events are posted there and these tasks run there, so plugin state touched only from events and tasks needs no
 * locking.
 *
 * <p>Tasks handed over before the API attaches wait, and run from the API's first poll on.
 */
@Singleton
public class ClientThread {

    private final Object lock = new Object();
    private final List<Consumer<GameSession>> waiting = new ArrayList<>();
    private GameSession session;

    @Inject
    ClientThread(ProcessAttacher attacher) {
        attacher.addAttachListener(this::attached);
        GameSession attached = attacher.attached();
        if (attached != null) {
            attached(attached);
        }
    }

    /**
     * Runs a task on the client thread: now if called on it, else at the next poll.
     *
     * @param task the task
     */
    public void invoke(Runnable task) {
        submit(session -> session.invoke(task));
    }

    /**
     * Runs a task on the client thread, now if called on it, else at the next poll, and again at each poll until it
     * returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invoke(BooleanSupplier task) {
        submit(session -> session.invoke(task));
    }

    /**
     * Runs a task on the client thread at the next poll, even when called on it.
     *
     * @param task the task
     */
    public void invokeLater(Runnable task) {
        submit(session -> session.invokeLater(task));
    }

    /**
     * Runs a task on the client thread at the next poll, and again at each poll until it returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invokeLater(BooleanSupplier task) {
        submit(session -> session.invokeLater(task));
    }

    /**
     * Runs a task on the client thread after the next game tick's events, {@code GameTick}'s subscribers included.
     *
     * @param task the task
     */
    public void invokeAtTickEnd(Runnable task) {
        submit(session -> session.invokeAtTickEnd(task));
    }

    /**
     * Whether the calling thread is the client thread.
     *
     * @return true on the API's poll thread
     */
    public boolean isClientThread() {
        GameSession attached;
        synchronized (lock) {
            attached = session;
        }
        return attached != null && attached.isClientThread();
    }

    private void submit(Consumer<GameSession> handoff) {
        GameSession attached;
        synchronized (lock) {
            if (session == null) {
                waiting.add(handoff);
                return;
            }
            attached = session;
        }
        handoff.accept(attached);
    }

    /**
     * Hands the tasks that waited for the API to it, in order. Runs on the attach thread, so none run here.
     *
     * @param attached the API that just attached
     */
    private void attached(GameSession attached) {
        synchronized (lock) {
            if (session != null) {
                return;
            }
            session = attached;
            waiting.forEach(handoff -> handoff.accept(attached));
            waiting.clear();
        }
    }
}
