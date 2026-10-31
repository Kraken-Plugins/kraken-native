package com.kraken.api.action;

/**
 * Whether the interaction methods send the mouse click packet before an action, as the game does for a real click,
 * and where the click lands. Set with {@code client.getInteractions().setClickPolicy(...)}; the point the policies
 * fall back to is {@code setStaticClickPoint(...)}, (0, 0) unless set.
 */
public enum ClickPolicy {

    /**
     * No click packet: the row only, as {@link Actions#menuAction} sends it.
     */
    NONE,

    /**
     * A click at the subject's point on the canvas when it is on the canvas, else at the static point, then the row.
     */
    ON_SCREEN,

    /**
     * A click at the static point, wherever the subject is, then the row.
     */
    STATIC
}
