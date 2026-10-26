package com.kraken.client.ui.overlay;

/**
 * Where an overlay goes. Anchored overlays at the same anchor stack: down from the top anchors, rightwards from the
 * bottom-left and leftwards from the bottom-right, each placed by the size it drew at last frame.
 *
 * <p>The anchors are the canvas's own corners. They aren't moved clear of the minimap, the chatbox or the inventory,
 * because Kraken doesn't read where those are yet, so {@code TOP_RIGHT} is the same corner as
 * {@code CANVAS_TOP_RIGHT} (and the two share one stack). Left out: {@code DETACHED} (an overlay the user drags keeps
 * its position and is drawn at its {@linkplain Overlay#getPreferredLocation() preferred location}), {@code TOOLTIP}
 * (it follows the mouse, which belongs to the game) and {@code ABOVE_CHATBOX_RIGHT} (it needs the chatbox's bounds).
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
