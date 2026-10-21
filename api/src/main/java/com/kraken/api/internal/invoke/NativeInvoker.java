package com.kraken.api.internal.invoke;

import java.util.List;

/**
 * Sends calls to the hook DLL, which runs them in order on the game thread at the start of its next frame. The
 * client's native hook provides one while its connection to the DLL is up and hands it to
 * {@code GameSession.setNativeInvoker}. Internal: plugins never need it.
 */
@FunctionalInterface
public interface NativeInvoker {

    /**
     * Queues a batch of calls. Returns once the batch is sent, not once it has run.
     *
     * @param batch the calls, run in this order
     * @throws java.io.UncheckedIOException if the connection to the DLL broke
     */
    void invoke(List<NativeCall> batch);
}
