package com.kraken.client.ui.overlay;

/**
 * When an overlay is drawn relative to the others, in drawing order.
 *
 * <p>Overlays are drawn in a window of their own above the game, so they can't be slotted in between the game's scene
 * and its interfaces. Every layer draws above the game's interfaces; the layers only order overlays among themselves.
 * There is no layer that draws after a chosen interface, for the same reason.
 */
public enum OverlayLayer {

    /**
     * Drawn first, right after the scene.
     */
    ABOVE_SCENE,

    /**
     * Drawn second; the default.
     */
    UNDER_WIDGETS,

    /**
     * Drawn third.
     */
    ABOVE_WIDGETS,

    /**
     * Last, over everything else.
     */
    ALWAYS_ON_TOP
}
