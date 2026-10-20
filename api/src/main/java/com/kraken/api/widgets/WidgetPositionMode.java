package com.kraken.api.widgets;

/**
 * How a widget's x or y is measured, as RuneLite's {@code WidgetPositionMode}: the values
 * {@link Widget#getXPositionMode()} and {@link Widget#getYPositionMode()} return. They are the cache's own values,
 * the same in the native client (Lua's {@code AlignMode}).
 */
public final class WidgetPositionMode {

    /**
     * Pixels from the parent's left edge.
     */
    public static final int ABSOLUTE_LEFT = 0;

    /**
     * Pixels from the parent's top edge.
     */
    public static final int ABSOLUTE_TOP = 0;

    /**
     * Pixels from the parent's centre.
     */
    public static final int ABSOLUTE_CENTER = 1;

    /**
     * Pixels from the parent's right edge.
     */
    public static final int ABSOLUTE_RIGHT = 2;

    /**
     * Pixels from the parent's bottom edge.
     */
    public static final int ABSOLUTE_BOTTOM = 2;

    /**
     * 1/16384ths of the parent's width from its left edge.
     */
    public static final int LEFT_16384THS = 3;

    /**
     * 1/16384ths of the parent's height from its top edge.
     */
    public static final int TOP_16384THS = 3;

    /**
     * 1/16384ths of the parent's size from its centre.
     */
    public static final int CENTER_16384THS = 4;

    /**
     * 1/16384ths of the parent's width from its right edge.
     */
    public static final int RIGHT_16384THS = 5;

    /**
     * 1/16384ths of the parent's height from its bottom edge.
     */
    public static final int BOTTOM_16384THS = 5;

    private WidgetPositionMode() {
    }
}
