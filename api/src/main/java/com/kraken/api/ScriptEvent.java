package com.kraken.api;

import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.widgets.Widget;

/**
 * What a root script ran with: the listener's arguments, and the component, op, mouse position and key it fired on.
 * {@code ScriptPreFired} carries one for each root script, captured by the native hook as the script started. A root
 * script the client ran from a trigger rather than a component's listener has only its id as an argument, no source
 * and -1 for the rest.
 *
 * <p>The arguments are the listener's as it holds them: the script's id first, then its arguments, which may hold the
 * placeholders below where the client passes the event's own values. They are a copy, so changing them doesn't
 * change what the script ran with.
 */
public final class ScriptEvent {

    /**
     * The placeholder for the mouse's x.
     */
    public static final int MOUSE_X = -2147483647;

    /**
     * The placeholder for the mouse's y.
     */
    public static final int MOUSE_Y = -2147483646;

    /**
     * The placeholder for the op, 1-based.
     */
    public static final int MENU_OP = -2147483644;

    /**
     * The placeholder for the source component's packed id.
     */
    public static final int WIDGET_ID = -2147483645;

    /**
     * The placeholder for the source component's index.
     */
    public static final int WIDGET_INDEX = -2147483643;

    /**
     * The placeholder for the target component's packed id.
     */
    public static final int WIDGET_TARGET_ID = -2147483642;

    /**
     * The placeholder for the target component's index.
     */
    public static final int WIDGET_TARGET_INDEX = -2147483641;

    /**
     * The placeholder for the typed key's code.
     */
    public static final int KEY_CODE = -2147483640;

    /**
     * The placeholder for the typed key's character.
     */
    public static final int KEY_CHAR = -2147483639;

    /**
     * The placeholder for the op's sub-option.
     */
    public static final int SUB_OP = -2147483638;

    /**
     * The string argument the client replaces with the op's base name.
     */
    public static final String NAME = "event_opbase";

    private final Client client;
    private final ScriptRequest request;

    /**
     * An event over a captured request.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client  the client the components are read from
     * @param request what the native hook captured
     */
    public ScriptEvent(Client client, ScriptRequest request) {
        this.client = client;
        this.request = request;
    }

    /**
     * The component whose listener ran the script, read now.
     *
     * @return the component (a dynamic one at its index), or null if there is none or it is gone
     */
    public Widget getSource() {
        return widget(request.sourceId(), request.sourceIndex());
    }

    /**
     * The component dragged onto or targeted, read now.
     *
     * @return the component, or null if there is none or it is gone
     */
    public Widget getTarget() {
        return widget(request.targetId(), request.targetIndex());
    }

    /**
     * The listener's arguments: the script's id first, then {@code Integer}s, {@code String}s and {@code Long}s (null
     * for other kinds). The native hook captures the first 24.
     *
     * @return a copy of the arguments
     */
    public Object[] getArguments() {
        return request.arguments().clone();
    }

    /**
     * The op that fired the listener.
     *
     * @return the op, 1-based; -1 for a script a trigger started
     */
    public int getOp() {
        return request.op();
    }

    /**
     * The op's sub-option, as the client passed it with the op.
     *
     * @return the sub-option; -1 for a script a trigger started
     */
    public int getSubOp() {
        return request.subOp();
    }

    /**
     * The op's base name, which a string argument {@link #NAME} stands for.
     *
     * @return the base name; "" for a script a trigger started
     */
    public String getOpbase() {
        return request.opbase();
    }

    /**
     * The mouse's x when the listener fired.
     *
     * @return the x; -1 for a script a trigger started
     */
    public int getMouseX() {
        return request.mouseX();
    }

    /**
     * The mouse's y when the listener fired.
     *
     * @return the y; -1 for a script a trigger started
     */
    public int getMouseY() {
        return request.mouseY();
    }

    /**
     * The typed key's code, for a key listener.
     *
     * @return the key code; -1 for a script a trigger started
     */
    public int getTypedKeyCode() {
        return request.keyCode();
    }

    /**
     * The typed key's character, for a key listener.
     *
     * @return the character; -1 for a script a trigger started
     */
    public int getTypedKeyChar() {
        return request.keyChar();
    }

    private Widget widget(int id, int index) {
        if (id == -1) {
            return null;
        }
        Widget widget = client.getWidget(id);
        return widget == null || index < 0 ? widget : widget.getChild(index);
    }
}
