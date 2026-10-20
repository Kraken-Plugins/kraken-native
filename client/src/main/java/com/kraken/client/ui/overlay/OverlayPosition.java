package com.kraken.client.ui.overlay;

/**
 * Where an overlay goes, as RuneLite's {@code OverlayPosition}. Anchored overlays at the same anchor stack: down from
 * the top anchors, rightwards from the bottom-left and leftwards from the bottom-right, each placed by the size it
 * drew at last frame, as RuneLite's renderer does.
 *
 * <p>Differs from RuneLite: the anchors are the canvas's own corners. RuneLite moves them clear of the minimap, the
 * chatbox and the inventory; Kraken doesn't read where those are yet, so {@code TOP_RIGHT} is the same corner as
 * {@code CANVAS_TOP_RIGHT} (and the two share one stack). Left out: {@code DETACHED} and {@code TOOLTIP} (they need
 * dragging and the mouse, which belong to the game), and {@code ABOVE_CHATBOX_RIGHT} (it needs the chatbox's bounds).
 */
public enum OverlayPosition {

    /**
     * Anywhere: the overlay draws over the whole canvas, at the positions it works out ({@code Perspective}).
     */
    DYNAMIC,

    /**
     * The top-left corner, below where the game shows the hovered option's text.
     */
    TOP_LEFT,

    /**
     * The middle of the top edge.
     */
    TOP_CENTER,

    /**
     * The top-right corner.
     */
    TOP_RIGHT,

    /**
     * The bottom-left corner.
     */
    BOTTOM_LEFT,

    /**
     * The bottom-right corner.
     */
    BOTTOM_RIGHT,

    /**
     * The canvas's top-right corner.
     */
    CANVAS_TOP_RIGHT
}
