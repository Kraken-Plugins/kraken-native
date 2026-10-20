package com.kraken.client.ui.overlay;

/**
 * Something that draws over the game on the EDT: the dev tools, and the finished frames of plugin overlays. Register
 * it with {@link GameOverlay#addPainter}. Plugins don't use this; they add an {@link Overlay} to the
 * {@link OverlayManager}.
 */
public interface OverlayPainter {

    /**
     * Whether there is anything to draw. The overlay window hides while no painter is active.
     *
     * @return true to be painted
     */
    boolean isActive();

    /**
     * Draws one frame. Runs on the EDT.
     *
     * @param overlay the window's graphics and size, in UI pixels
     */
    void paint(OverlayContext overlay);
}
