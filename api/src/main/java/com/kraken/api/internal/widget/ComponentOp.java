package com.kraken.api.internal.widget;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One write to an interface component, as the native hook takes it: the component by id, what to do, at which offset
 * in the client's component and the value or text. The hook looks the component up on the game thread, so a write to
 * one that has gone does nothing. Internal: {@code Widget}'s setters make these and {@link ComponentWrites} sends them.
 *
 * @param packedId the component's {@code group << 16 | component}
 * @param subId    its index among its static parent's dynamic children, or -1 for a static component
 * @param op       what to do
 * @param offset   the field's offset in the client's component
 * @param value    the value {@link Op#SET_I32} writes; 0 for the other ops
 * @param text     the text {@link Op#SET_TEXT} sets; null for the other ops
 */
public record ComponentOp(int packedId, int subId, Op op, int offset, int value, String text) {

    /**
     * The longest text {@link Op#SET_TEXT} sets, in UTF-8 bytes, as many as the hook takes.
     */
    public static final int MAX_TEXT = 4096;

    /**
     * Checks the op is set, the offset fits the wire format and a text write carries a text the hook takes.
     *
     * @param packedId the component's packed id
     * @param subId    its index, or -1
     * @param op       what to do
     * @param offset   the field's offset
     * @param value    the value written
     * @param text     the text set
     */
    public ComponentOp {
        Objects.requireNonNull(op, "op");
        if (offset < 0 || offset > 0xffff) {
            throw new IllegalArgumentException("offset must be 0-65535, got " + offset);
        }
        if (op == Op.SET_TEXT) {
            Objects.requireNonNull(text, "text");
            int length = text.getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_TEXT) {
                throw new IllegalArgumentException("text must be at most " + MAX_TEXT + " UTF-8 bytes, got " + length);
            }
        }
    }

    /**
     * A write that carries no text.
     *
     * @param packedId the component's packed id
     * @param subId    its index, or -1
     * @param op       what to do
     * @param offset   the field's offset
     * @param value    the value {@link Op#SET_I32} writes; 0 for the other ops
     */
    public ComponentOp(int packedId, int subId, Op op, int offset, int value) {
        this(packedId, subId, op, offset, value, null);
    }

    /**
     * What a write does; the ordinals are the hook's op codes.
     */
    public enum Op {

        /**
         * Writes an int32 field.
         */
        SET_I32,

        /**
         * Clears the listener held at the offset, through the client's own function, which frees its arguments.
         */
        CLEAR_LISTENER,

        /**
         * Sets the string held at the offset as {@code cc_settext} does: assigns the text through the client's own
         * function unless the string already holds it, then marks the component changed, so the client redraws it.
         */
        SET_TEXT
    }
}
