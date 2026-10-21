package com.kraken.api.internal.invoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One call of a client function, which the hook DLL makes on the game thread: the function's RVA, up to
 * {@link #MAX_ARGS} integer arguments and a block of bytes the arguments can point into. The DLL never interprets a
 * call; which function, which arguments and what the bytes hold all come from the API and the mapping.
 * Internal: built by the API's own packages, sent by the client's hook.
 *
 * @param rva  the function's RVA (module-base-relative)
 * @param args the arguments in order: RCX, RDX, R8, R9, then the stack
 * @param data bytes copied into the game for the call, which {@link Kind#DATA} arguments point into
 */
public record NativeCall(long rva, List<Arg> args, byte[] data) {

    /**
     * The most arguments a call can take.
     */
    public static final int MAX_ARGS = 8;

    /**
     * The largest data block a call can carry.
     */
    public static final int MAX_DATA = 0xffff;

    /**
     * Copies the arguments and data, so a call can't change after it is built.
     *
     * @param rva  the function's RVA
     * @param args the arguments
     * @param data the data block
     */
    public NativeCall {
        args = List.copyOf(args);
        if (args.size() > MAX_ARGS) {
            throw new IllegalArgumentException("at most " + MAX_ARGS + " arguments, got " + args.size());
        }
        data = data.clone();
        if (data.length > MAX_DATA) {
            throw new IllegalArgumentException("at most " + MAX_DATA + " data bytes, got " + data.length);
        }
    }

    /**
     * Starts building a call.
     *
     * @param rva the function's RVA
     * @return a builder
     */
    public static Builder to(long rva) {
        return new Builder(rva);
    }

    /**
     * The call's data block.
     *
     * @return a copy of the bytes
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NativeCall call && rva == call.rva && args.equals(call.args)
                && Arrays.equals(data, call.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rva, args, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "NativeCall[0x" + Long.toHexString(rva) + " " + args + " data=" + data.length + "]";
    }

    /**
     * How the DLL turns an argument's value into what it passes.
     */
    public enum Kind {

        /**
         * The value itself.
         */
        IMM,

        /**
         * The {@code Client} object's address plus the value. The DLL skips the call while the client pointer is null.
         */
        CLIENT,

        /**
         * A pointer to the call's data block plus the value.
         */
        DATA
    }

    /**
     * One argument.
     *
     * @param kind  how the DLL computes it
     * @param value the value, the offset from the client, or the offset into the data
     */
    public record Arg(Kind kind, long value) {

        /**
         * Checks the kind is set.
         *
         * @param kind  how the DLL computes it
         * @param value its value
         */
        public Arg {
            Objects.requireNonNull(kind, "kind");
        }

        /**
         * An argument passed as it is.
         *
         * @param value the value
         * @return the argument
         */
        public static Arg imm(long value) {
            return new Arg(Kind.IMM, value);
        }

        /**
         * The address of a field of the {@code Client} object.
         *
         * @param offset the field's offset
         * @return the argument
         */
        public static Arg client(long offset) {
            return new Arg(Kind.CLIENT, offset);
        }

        /**
         * A pointer into the call's data block.
         *
         * @param offset the offset into the block
         * @return the argument
         */
        public static Arg data(long offset) {
            return new Arg(Kind.DATA, offset);
        }
    }

    /**
     * Builds a call argument by argument, appending each data argument's bytes to the call's block.
     */
    public static final class Builder {

        private final long rva;
        private final List<Arg> args = new ArrayList<>();
        private byte[] data = new byte[0];

        private Builder(long rva) {
            this.rva = rva;
        }

        /**
         * Adds an argument passed as it is.
         *
         * @param value the value
         * @return this builder
         */
        public Builder imm(long value) {
            args.add(Arg.imm(value));
            return this;
        }

        /**
         * Adds the address of a field of the {@code Client} object.
         *
         * @param offset the field's offset
         * @return this builder
         */
        public Builder client(long offset) {
            args.add(Arg.client(offset));
            return this;
        }

        /**
         * Adds a pointer to a copy of some bytes, placed 8-byte aligned in the call's data block.
         *
         * @param bytes what the pointer points at
         * @return this builder
         */
        public Builder data(byte[] bytes) {
            int offset = (data.length + 7) & ~7;
            byte[] grown = Arrays.copyOf(data, offset + bytes.length);
            System.arraycopy(bytes, 0, grown, offset, bytes.length);
            data = grown;
            args.add(Arg.data(offset));
            return this;
        }

        /**
         * Finishes the call.
         *
         * @return the call
         */
        public NativeCall build() {
            return new NativeCall(rva, args, data);
        }
    }
}
