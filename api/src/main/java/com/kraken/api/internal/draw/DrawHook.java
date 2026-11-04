package com.kraken.api.internal.draw;

import java.util.List;

/**
 * Sends the draw list to the native hook, which keeps the items and draws them in every frame, resolving their anchors
 * from the state that frame was drawn with. Internal: the client's hook server hands one to
 * {@code GameSession.setDrawHook} while a hook that draws the list is connected; {@code DrawList} sends through it.
 */
public interface DrawHook {

    /**
     * Copies sprites into the hook's atlas. Sent before the list that uses them.
     *
     * @param sprites the sprites and where each goes
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void sprites(List<WireSprite> sprites);

    /**
     * Replaces the items the hook holds. An empty list draws nothing.
     *
     * @param list the items
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void list(WireDrawList list);
}
