package com.kraken.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Something that draws itself: an {@link Overlay}, or a panel component.
 */
public interface RenderableEntity {

    /**
     * Draws one frame. For overlays this runs on the client thread, in the client's canvas pixels.
     *
     * @param graphics where to draw
     * @return the size drawn, or null if nothing was (always null is fine for {@link OverlayPosition#DYNAMIC})
     */
    Dimension render(Graphics2D graphics);
}
