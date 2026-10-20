package com.kraken.api.widgets;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Widget types: the values {@link Widget#getType()} returns. They are the cache's own values, the same in the native
 * client (Lua's {@code IfTypes}), which also names {@link #INPUT_BOX}, {@link #CIRCLE} and {@link #CRM_VIEW}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WidgetType {

    /**
     * A container; only a layer has children.
     */
    public static final int LAYER = 0;

    /**
     * An input box. Lua's {@code IfTypes.INPUTBOX}.
     */
    public static final int INPUT_BOX = 1;

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
     * A circle. Lua's {@code IfTypes.CIRCLE}.
     */
    public static final int CIRCLE = 10;

    /**
     * Lua's {@code IfTypes.CRMVIEW}.
     */
    public static final int CRM_VIEW = 11;

    /**
     * A text input field.
     */
    public static final int INPUT_FIELD = 12;

    /**
     * A type's name, as Lua's {@code IfTypes} names it.
     *
     * @param type the type, e.g. {@link Widget#getType()}
     * @return its Lua name, e.g. "LAYER", or "TYPE_n" for a value Lua doesn't name
     */
    public static String name(int type) {
        return switch (type) {
            case LAYER -> "LAYER";
            case INPUT_BOX -> "INPUTBOX";
            case RECTANGLE -> "RECT";
            case TEXT -> "TEXT";
            case GRAPHIC -> "GRAPHIC";
            case MODEL -> "MODEL";
            case IF1_TOOLTIP -> "TOOLTIP";
            case LINE -> "LINE";
            case CIRCLE -> "CIRCLE";
            case CRM_VIEW -> "CRMVIEW";
            case INPUT_FIELD -> "INPUTFIELD";
            default -> "TYPE_" + type;
        };
    }
}
