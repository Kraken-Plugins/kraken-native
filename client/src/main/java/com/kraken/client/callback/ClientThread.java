package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;

import java.util.function.BooleanSupplier;

/**
 * Runs code on the client thread. Kraken's client thread is the API's poll thread ({@code kraken-api-poll}): game
 * events are posted there and these tasks run there, so plugin state touched only from events and tasks needs no
 * locking.
 *
 * <p>It lives in the session injector, so it exists once the API has attached, as plugins do. Tasks handed over
 * before the poll loop starts run from its first poll.
 */
@Singleton
public class ClientThread {

    private final GameSession session;

    /**
     * Creates the client thread for the attached game.
     *
     * @param session the attached API, whose poll thread runs the tasks
     */
    @Inject
    ClientThread(GameSession session) {
        this.session = session;
    }

    /**
     * Runs a task on the client thread: now if called on it, else at the next poll.
     *
     * @param task the task
     */
    public void invoke(Runnable task) {
        session.invoke(task);
    }

    /**
     * Runs a task on the client thread, now if called on it, else at the next poll, and again at each poll until it
     * returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invoke(BooleanSupplier task) {
        session.invoke(task);
    }

    /**
     * Runs a task on the client thread at the next poll, even when called on it.
     *
     * @param task the task
     */
    public void invokeLater(Runnable task) {
        session.invokeLater(task);
    }

    /**
     * Runs a task on the client thread at the next poll, and again at each poll until it returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invokeLater(BooleanSupplier task) {
        session.invokeLater(task);
    }

    /**
     * Runs a task on the client thread after the next game tick's events, {@code GameTick}'s subscribers included.
     *
     * @param task the task
     */
    public void invokeAtTickEnd(Runnable task) {
        session.invokeAtTickEnd(task);
    }

    /**
     * Whether the calling thread is the client thread.
     *
     * @return true on the API's poll thread
     */
    public boolean isClientThread() {
        return session.isClientThread();
    }
}
