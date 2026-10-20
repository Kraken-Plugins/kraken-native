package com.kraken.api.widgets;

/**
 * How a widget's width or height is measured, as RuneLite's {@code WidgetSizeMode}: the values
 * {@link Widget#getWidthMode()} and {@link Widget#getHeightMode()} return. They are the cache's own values, the same
 * in the native client (Lua's {@code SizeMode}).
 */
public final class WidgetSizeMode {

    /**
     * The size is the designed size.
     */
    public static final int ABSOLUTE = 0;

    /**
     * The size is the parent's, less the designed size.
     */
    public static final int MINUS = 1;

    /**
     * The size is the parent's times the designed size over 16384.
     */
    public static final int ABSOLUTE_16384THS = 2;

    private WidgetSizeMode() {
    }
}
