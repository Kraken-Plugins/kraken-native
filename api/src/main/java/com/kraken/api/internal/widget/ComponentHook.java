package com.kraken.api.internal.widget;

import java.util.List;

/**
 * Sends component writes to the native hook, which makes them on the game thread at the start of the next frame.
 * Internal: the client's hook server hands one to {@code GameSession.setComponentHook} while a hook that writes
 * components is connected; {@link ComponentWrites} sends through it.
 */
@FunctionalInterface
public interface ComponentHook {

    /**
     * Sends a batch of writes, made in this order.
     *
     * @param ops the writes
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void apply(List<ComponentOp> ops);
}
