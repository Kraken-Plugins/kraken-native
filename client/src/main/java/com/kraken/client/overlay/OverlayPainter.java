package com.kraken.client.overlay;

/** Something that draws over the game. Register it with {@link GameOverlay#addPainter}. */
public interface OverlayPainter {
    /** Whether there is anything to draw. The overlay window hides while no painter is active. */
    boolean isActive();

    /** Draws one frame. Runs on the EDT. */
    void paint(OverlayContext overlay);
}
