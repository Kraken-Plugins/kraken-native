package com.kraken.client.callback;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.KrakenApi;
import com.kraken.client.ApiConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs code on the client thread, as RuneLite's {@code ClientThread}. Kraken's client thread is the API's poll thread
 * ({@code kraken-api-poll}): game events are posted there and these tasks run there, so plugin state touched only from
 * events and tasks needs no locking.
 *
 * <p>Tasks handed over before the API attaches wait, and run from the API's first poll on.
 */
@Singleton
public class ClientThread {

    private final Object lock = new Object();
    private final List<Consumer<KrakenApi>> waiting = new ArrayList<>();
    private KrakenApi api;

    @Inject
    ClientThread(ApiConnection connection) {
        connection.addAttachListener(this::attached);
        KrakenApi attached = connection.api();
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
        submit(api -> api.invoke(task));
    }

    /**
     * Runs a task on the client thread, now if called on it, else at the next poll, and again at each poll until it
     * returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invoke(BooleanSupplier task) {
        submit(api -> api.invoke(task));
    }

    /**
     * Runs a task on the client thread at the next poll, even when called on it.
     *
     * @param task the task
     */
    public void invokeLater(Runnable task) {
        submit(api -> api.invokeLater(task));
    }

    /**
     * Runs a task on the client thread at the next poll, and again at each poll until it returns true.
     *
     * @param task the task; false asks to run it again at the next poll
     */
    public void invokeLater(BooleanSupplier task) {
        submit(api -> api.invokeLater(task));
    }

    /**
     * Runs a task on the client thread after the next game tick's events, {@code GameTick}'s subscribers included.
     *
     * @param task the task
     */
    public void invokeAtTickEnd(Runnable task) {
        submit(api -> api.invokeAtTickEnd(task));
    }

    /**
     * Whether the calling thread is the client thread. Kraken extra: RuneLite has this on {@code Client}.
     *
     * @return true on the API's poll thread
     */
    public boolean isClientThread() {
        KrakenApi attached;
        synchronized (lock) {
            attached = api;
        }
        return attached != null && attached.isClientThread();
    }

    private void submit(Consumer<KrakenApi> handoff) {
        KrakenApi attached;
        synchronized (lock) {
            if (api == null) {
                waiting.add(handoff);
                return;
            }
            attached = api;
        }
        handoff.accept(attached);
    }

    /**
     * Hands the tasks that waited for the API to it, in order. Runs on the attach thread, so none run here.
     *
     * @param attached the API that just attached
     */
    private void attached(KrakenApi attached) {
        synchronized (lock) {
            if (api != null) {
                return;
            }
            api = attached;
            waiting.forEach(handoff -> handoff.accept(attached));
            waiting.clear();
        }
    }
}
