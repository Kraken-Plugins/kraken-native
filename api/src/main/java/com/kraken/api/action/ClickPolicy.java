package com.kraken.api.action;

/**
 * Whether the interaction methods send the mouse click packets before an action, as the game does for a real click,
 * and where the click lands. Set with {@code client.getInteractions().setClickPolicy(...)}; the point the policies
 * fall back to is {@code setStaticClickPoint(...)}, (0, 0) unless set.
 */
public enum ClickPolicy {

    /**
     * No click packets: the row only, as {@link Actions#menuAction} sends it.
     */
    NONE("None"),

    /**
     * A click at the subject's point on the canvas when it is on the canvas, else at the static point, then the row.
     */
    ON_SCREEN("On screen"),

    /**
     * A click at the static point, wherever the subject is, then the row.
     */
    STATIC("Static point");

    private final String label;

    ClickPolicy(String label) {
        this.label = label;
    }

    /**
     * The policy's name as a settings page shows it.
     *
     * @return e.g. "On screen"
     */
    @Override
    public String toString() {
        return label;
    }
}
