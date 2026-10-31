package com.kraken.api.internal.widget;

import java.util.Objects;

/**
 * One write to an interface component, as the native hook takes it: the component by id, what to do, at which offset
 * in the client's component and the value. The hook looks the component up on the game thread, so a write to one that
 * has gone does nothing. Internal: {@code Widget}'s setters make these and {@link ComponentWrites} sends them.
 *
 * @param packedId the component's {@code group << 16 | component}
 * @param subId    its index among its static parent's dynamic children, or -1 for a static component
 * @param op       what to do
 * @param offset   the field's offset in the client's component
 * @param value    the value {@link Op#SET_I32} writes; 0 for the other ops
 */
public record ComponentOp(int packedId, int subId, Op op, int offset, int value) {

    /**
     * Checks the op is set and the offset fits the wire format.
     *
     * @param packedId the component's packed id
     * @param subId    its index, or -1
     * @param op       what to do
     * @param offset   the field's offset
     * @param value    the value written
     */
    public ComponentOp {
        Objects.requireNonNull(op, "op");
        if (offset < 0 || offset > 0xffff) {
            throw new IllegalArgumentException("offset must be 0-65535, got " + offset);
        }
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
        CLEAR_LISTENER
    }
}
