package com.kraken.api.internal.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The component writes waiting to be sent: {@code Widget}'s setters add them, and the client thread sends what is
 * waiting together at the end of each pass ({@link #flush}), so the writes of one event handler reach the game in the
 * same frame. A write is a one-off: one made while no hook that writes components is connected is dropped, not kept
 * for the next connection, since the component it names may be long gone by then. Internal: plugins write through
 * {@code Widget}. Thread-safe.
 */
public final class ComponentWrites {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The most writes sent in one command, as many as the hook keeps waiting.
     */
    static final int MAX_BATCH = 4096;

    private final List<ComponentOp> waiting = new ArrayList<>();
    private ComponentHook hook;

    /**
     * Whether writes reach the game now: a hook that writes components is connected.
     *
     * @return true while writes are sent
     */
    public synchronized boolean isAvailable() {
        return hook != null;
    }

    /**
     * Takes the connection to a hook that writes components, or drops it, dropping what is waiting with it.
     *
     * @param hook the connection, or null while there is none
     */
    public synchronized void setHook(ComponentHook hook) {
        this.hook = hook;
        waiting.clear();
    }

    /**
     * Queues a write for the next {@link #flush}.
     *
     * @param op the write
     * @return false if no hook is connected, so the write was dropped
     */
    public synchronized boolean add(ComponentOp op) {
        if (hook == null) {
            return false;
        }
        waiting.add(op);
        return true;
    }

    /**
     * Sends the waiting writes, in the order they were made. Called by the client thread at the end of each pass.
     */
    public void flush() {
        List<ComponentOp> batch;
        ComponentHook target;
        synchronized (this) {
            if (waiting.isEmpty() || hook == null) {
                return;
            }
            batch = List.copyOf(waiting);
            waiting.clear();
            target = hook;
        }
        try {
            for (int from = 0; from < batch.size(); from += MAX_BATCH) {
                target.apply(batch.subList(from, Math.min(batch.size(), from + MAX_BATCH)));
            }
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not send component writes to the native hook", e);
        }
    }
}
