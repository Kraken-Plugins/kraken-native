package com.kraken.api.widgets;

/**
 * Widget types, as RuneLite's {@code WidgetType}: the values {@link Widget#getType()} returns. They are the cache's own
 * values, the same in the native client (Lua's {@code IfTypes}), which has three RuneLite doesn't name: 1
 * ({@code INPUTBOX}), 10 ({@code CIRCLE}) and 11 ({@code CRMVIEW}).
 */
public final class WidgetType {

    /**
     * A container; only a layer has children.
     */
    public static final int LAYER = 0;

    /**
     * A filled or outlined rectangle.
     */
    public static final int RECTANGLE = 3;

    /**
     * Text.
     */
    public static final int TEXT = 4;

    /**
     * A sprite, or an item in an inventory slot.
     */
    public static final int GRAPHIC = 5;

    /**
     * A model.
     */
    public static final int MODEL = 6;

    /**
     * An old-style inventory of text items.
     */
    public static final int TEXT_INVENTORY = 7;

    /**
     * An old-style tooltip.
     */
    public static final int IF1_TOOLTIP = 8;

    /**
     * A line.
     */
    public static final int LINE = 9;

    /**
     * A text input field.
     */
    public static final int INPUT_FIELD = 12;

    private WidgetType() {
    }
}
