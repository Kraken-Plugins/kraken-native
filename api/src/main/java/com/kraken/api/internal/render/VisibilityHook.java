package com.kraken.api.internal.render;

/**
 * Sends the visibility rules to the native hook, which keeps them and applies them on the game thread at the start of
 * every frame. Internal: the client's hook server hands one to {@code GameSession.setVisibilityHook} while a hook that
 * hides entities is connected; {@code EntityVisibility} sends through it.
 */
@FunctionalInterface
public interface VisibilityHook {

    /**
     * Replaces the rules the hook holds. Rules that hide nothing show again everything the hook hid.
     *
     * @param rules the rules
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void apply(WireVisibility rules);
}
