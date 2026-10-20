package com.kraken.client.ui.overlay;

/**
 * When an overlay is drawn relative to the others, as RuneLite's {@code OverlayLayer}, in drawing order.
 *
 * <p>Differs from RuneLite: Kraken draws in a window of its own above the game, so it can't slot overlays in between
 * the game's scene and its interfaces. Every layer draws above the game's interfaces; the layers only order overlays
 * among themselves. RuneLite's {@code MANUAL} layer (drawing after a chosen interface) is left out for the same reason.
 */
public enum OverlayLayer {

    /**
     * First, as in RuneLite where it draws right after the scene.
     */
    ABOVE_SCENE,

    /**
     * Second; the default, as in RuneLite.
     */
    UNDER_WIDGETS,

    /**
     * Third, as in RuneLite where it draws over the interfaces.
     */
    ABOVE_WIDGETS,

    /**
     * Last, over everything else.
     */
    ALWAYS_ON_TOP
}
