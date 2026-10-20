package com.kraken.client.ui.overlay.components;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Color;

/**
 * Shared sizes and colours of the panel components.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ComponentConstants {

    /**
     * A panel's usual width, in canvas pixels.
     */
    public static final int STANDARD_WIDTH = 129;

    /**
     * A panel's usual inner border.
     */
    public static final int STANDARD_BORDER = 4;

    /**
     * A panel's usual background: a translucent brown.
     */
    public static final Color STANDARD_BACKGROUND_COLOR = new Color(70, 61, 50, 156);
}
