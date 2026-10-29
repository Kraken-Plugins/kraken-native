package com.kraken.api.widgets;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The modes an interface is opened in a component with ({@link WidgetNode#getModalMode()},
 * {@code WidgetClosed.getModalMode()}). The modal ones close together when the player presses Escape or walks away,
 * or when the server closes the modal interfaces.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WidgetModalMode {

    /**
     * Modal, and clicks outside it don't reach what is behind it.
     */
    public static final int MODAL_NOCLICKTHROUGH = 0;

    /**
     * Not modal: stays open when the modal interfaces close (the side panels, the chatbox, overlays).
     */
    public static final int NON_MODAL = 1;

    /**
     * Modal, and clicks outside it reach what is behind it.
     */
    public static final int MODAL_CLICKTHROUGH = 3;
}
