package com.kraken.client.callback;

import com.kraken.api.internal.NativeEvent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The wire format between {@link NativeEventServer} and {@code kraken_hook.dll} (protocol v2, little-endian). Java
 * sends one handshake naming the raise functions to hook, their event ids and a capture plan per slot; the DLL sends
 * one message per raise with the id, the argument registers and the captured values. Every field and op code here
 * matches the constants in {@code kraken_hook.cpp}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeProtocol {

    /** Handshake magic: identifies protocol v2. */
    static final byte[] MAGIC = {'K', 'R', 'K', '2'};

    /** Capture-plan ops: how the DLL reads the argument register a plan entry names. */
    static final int OP_RAW = 0;   // the register value itself (u64)
    static final int OP_I32 = 1;   // int32 at [register]
    static final int OP_I64 = 2;   // int64 at [register]
    static final int OP_I32X2 = 3; // two int32 at [register]
    static final int OP_CSTR = 4;  // NUL-terminated bytes at [register]

    /** A reserved event id the DLL sends once after a handshake to report how many hooks installed. */
    static final int STATUS_EVENT_ID = -2;

    /**
     * One capture-plan entry: read argument register {@code arg} ({@link NativeEvent#RCX}…{@link NativeEvent#R9})
     * with op {@code op}.
     *
     * @param arg the argument register to read
     * @param op  how to read it (one of the {@code OP_*} ops)
     */
    record Plan(int arg, int op) {
    }

    /**
     * One raise function to hook: where it is and how the DLL should read the event id and payload from it.
     *
     * @param rva     the raiser's RVA (module-base-relative)
     * @param fixedId the event id this raiser always carries, or -1 to read it from {@code idArg}
     * @param idArg   the argument register holding the id when {@code fixedId} is -1
     * @param args    how many argument registers to forward (0..4)
     * @param plan    the capture plan: which registers to read and how
     */
    record Slot(long rva, int fixedId, int idArg, int args, List<Plan> plan) {
    }

    /**
     * Builds the handshake: magic, a slot count, then each slot's rva, id configuration, argument count and plan.
     *
     * @param slots the slots to install
     * @return the handshake bytes
     */
    static byte[] handshake(List<Slot> slots) {
        int size = MAGIC.length + 2;
        for (Slot slot : slots) {
            size += 8 + 4 + 1 + 1 + 1 + slot.plan().size() * 2;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putShort((short) slots.size());
        for (Slot slot : slots) {
            buf.putLong(slot.rva());
            buf.putInt(slot.fixedId());
            buf.put((byte) slot.idArg());
            buf.put((byte) slot.args());
            buf.put((byte) slot.plan().size());
            for (Plan plan : slot.plan()) {
                buf.put((byte) plan.arg());
                buf.put((byte) plan.op());
            }
        }
        return buf.array();
    }

    /**
     * Parses one message body (the bytes after the 4-byte length frame) into a {@link NativeEvent}.
     *
     * @param body     the message body
     * @param idToName the event id to its name (null for an id the mapping doesn't know)
     * @return the parsed event; its {@link NativeEvent#name()} is null for an unknown id
     */
    static NativeEvent parse(byte[] body, IntFunction<String> idToName) {
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        long seq = buf.getInt() & 0xffffffffL;
        int eventId = buf.getInt();
        long[] regs = new long[4];
        for (int i = 0; i < regs.length; i++) {
            regs[i] = buf.getLong();
        }
        int nValues = buf.get() & 0xff;
        Map<Integer, Object> values = new HashMap<>();
        for (int i = 0; i < nValues; i++) {
            int arg = buf.get() & 0xff;
            int op = buf.get() & 0xff;
            Object value = switch (op) {
                case OP_RAW, OP_I64 -> buf.getLong();
                case OP_I32 -> buf.getInt();
                case OP_I32X2 -> new int[]{buf.getInt(), buf.getInt()};
                case OP_CSTR -> {
                    int length = buf.get() & 0xff;
                    byte[] bytes = new byte[length];
                    buf.get(bytes);
                    yield new String(bytes, StandardCharsets.US_ASCII);
                }
                default -> null;
            };
            if (value != null) {
                values.put(arg, value);
            }
        }
        return new NativeEvent(idToName.apply(eventId), eventId, regs, values, seq);
    }
}
