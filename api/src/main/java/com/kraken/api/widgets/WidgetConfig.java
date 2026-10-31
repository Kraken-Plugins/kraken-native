package com.kraken.api.widgets;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The bits of a widget's click mask ({@link Widget#getClickMask()}), as the client's menu, drag and selection code
 * tests them.
 *
 * <p>Bits 11-16 are the target mask: what a spell or item selected from the widget can be used on. The client copies
 * them into the selection when the widget is selected, and each kind of menu row for a target checks its own bit; a
 * widget with any of them set also gets the row that selects it (a spell's Cast). No menu row tests bit 15. Bits 17-19
 * hold how many layers up from the widget a drag moves: 0 for a widget that doesn't drag, 1 ({@link #DRAG}) for the
 * widget itself, 2 for its layer and so on.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WidgetConfig {

    /**
     * The widget is a pause button: its menu has a Continue row ({@code MenuAction.WIDGET_CONTINUE}), which resumes the
     * script waiting on it.
     */
    public static final int PAUSE_BUTTON = 1;

    /**
     * Sends op 9 to the server: the same bit as {@code transmitAction(8)}.
     */
    public static final int SHOW_MENU_OPTION_NINE = 1 << 9;

    /**
     * A selection from this widget can be used on an item on the ground.
     */
    public static final int USE_GROUND_ITEM = 1 << 11;

    /**
     * A selection from this widget can be used on an NPC.
     */
    public static final int USE_NPC = 2 << 11;

    /**
     * A selection from this widget can be used on an object (a loc).
     */
    public static final int USE_OBJECT = 4 << 11;

    /**
     * A selection from this widget can be used on a player.
     */
    public static final int USE_PLAYER = 8 << 11;

    /**
     * A selection from this widget can be used on a widget that has {@link #WIDGET_USE_TARGET}, such as an inventory
     * item.
     */
    public static final int USE_WIDGET = 32 << 11;

    /**
     * The widget can be dragged: the lowest bit of the drag depth (bits 17-19), so a depth of 1, the widget itself.
     */
    public static final int DRAG = 1 << 17;

    /**
     * Other widgets can be dragged onto this widget: it becomes the drag's target while the mouse is over it.
     */
    public static final int DRAG_ON = 1 << 20;

    /**
     * A selection whose target mask has {@link #USE_WIDGET} can be used on this widget.
     */
    public static final int WIDGET_USE_TARGET = 1 << 21;

    /**
     * The bit that sends one of the widget's first ten ops to the server when its menu row is clicked. It holds for a
     * widget's own events; for a slot the server set events on, the client takes the ops it sends from an op mask the
     * server sets alongside them (up to 32 ops), so these bits may not match it.
     *
     * @param action the op, 0-based: 0 for op 1, up to 9 for op 10
     * @return the op's bit, {@code 1 << (action + 1)}
     */
    public static int transmitAction(int action) {
        return 1 << (action + 1);
    }
}
