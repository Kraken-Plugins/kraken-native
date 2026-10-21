package com.kraken.client.callback;

import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.invoke.NativeCall;
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
 * The wire format between {@link NativeEventServer} and {@code kraken_hook.dll} (protocol v3, little-endian). Java
 * sends one handshake naming the raise functions to hook, their event ids and a capture plan per slot, then the
 * functions the drawing needs; the DLL sends one message per raise with the id, the argument registers and the
 * captured values, and status messages with reserved ids. After the handshake Java sends commands: batches of calls
 * for the game thread, the overlay surface to draw and the frame cap. Every field and op code here matches the
 * constants in {@code kraken_hook.cpp}; {@code docs/in-game-drawing.md} describes the format.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeProtocol {

    /**
     * Handshake magic: identifies protocol v3.
     */
    static final byte[] MAGIC = {'K', 'R', 'K', '3'};

    /**
     * Capture-plan ops: how the DLL reads the argument register a plan entry names.
     */
    static final int OP_RAW = 0;   // the register value itself (u64)
    static final int OP_I32 = 1;   // int32 at [register]
    static final int OP_I64 = 2;   // int64 at [register]
    static final int OP_I32X2 = 3; // two int32 at [register]
    static final int OP_CSTR = 4;  // NUL-terminated bytes at [register]

    /**
     * A reserved event id the DLL sends once after a handshake: hooks installed and requested, features, version.
     */
    static final int STATUS_EVENT_ID = -2;

    /**
     * A reserved event id the DLL sends when the game's framebuffer, GL version or drawn surface changes.
     */
    static final int FRAME_EVENT_ID = -3;

    /**
     * A reserved event id the DLL sends after running a batch of calls: batch id, calls run, calls faulted or
     * skipped, the last return value.
     */
    static final int CALLS_DONE_EVENT_ID = -4;

    /**
     * A reserved event id the DLL sends about once a second: frames the game presented since the last one, how long
     * that took in microseconds, the frame cap in force, and 1 if it counted presents (0: frame steps).
     */
    static final int FPS_EVENT_ID = -5;

    /**
     * The STATUS feature bit for the game-thread pump: the DLL runs calls.
     */
    static final long FEATURE_CALLS = 1;

    /**
     * The STATUS feature bit for the present hook: the DLL draws the overlay surface.
     */
    static final long FEATURE_OVERLAY = 2;

    /**
     * The STATUS feature bit for frame pacing: the DLL sends {@link #FPS_EVENT_ID} and, with the pump, holds the game
     * to the cap {@link #COMMAND_FRAME_LIMIT} sets.
     */
    static final long FEATURE_FRAME_RATE = 4;

    /**
     * Command type: a batch of calls.
     */
    static final int COMMAND_CALLS = 1;

    /**
     * Command type: the overlay surface to draw.
     */
    static final int COMMAND_SURFACE = 2;

    /**
     * Command type: the frame rate to hold the game to.
     */
    static final int COMMAND_FRAME_LIMIT = 3;

    /**
     * One capture-plan entry: read argument register {@code arg} ({@link NativeEvent#RCX}…{@link NativeEvent#R9})
     * with op {@code op}.
     *
     * @param arg the argument register to read
     * @param op  how to read it (one of the {@code OP_*} ops)
     */
    record Plan(int arg, int op) {}

    /**
     * One raise function to hook: where it is and how the DLL should read the event id and payload from it.
     *
     * @param rva     the raiser's RVA (module-base-relative)
     * @param fixedId the event id this raiser always carries, or -1 to read it from {@code idArg}
     * @param idArg   the argument register holding the id when {@code fixedId} is -1
     * @param args    how many argument registers to forward (0..4)
     * @param plan    the capture plan: which registers to read and how
     */
    record Slot(long rva, int fixedId, int idArg, int args, List<Plan> plan) {}

    /**
     * The functions the drawing needs, which the handshake names after the slots. A zero RVA leaves that part off.
     *
     * @param clientGlobal the RVA of the {@code Client*} slot ({@code globals.client})
     * @param pumpFn       the per-frame step on the game thread ({@code functions.appUpdate}), where calls run
     * @param presentFn    the frame's present ({@code functions.present}), where the overlay is drawn
     */
    record Drawing(long clientGlobal, long pumpFn, long presentFn) {

        /**
         * No calls and no overlay.
         */
        static final Drawing NONE = new Drawing(0, 0, 0);
    }

    /**
     * Builds the handshake: magic, a slot count, then each slot's rva, id configuration, argument count and plan, then
     * the drawing functions.
     *
     * @param slots   the slots to install
     * @param drawing the client pointer, the pump and the present function
     * @return the handshake bytes
     */
    static byte[] handshake(List<Slot> slots, Drawing drawing) {
        int size = MAGIC.length + 2 + 3 * 8;
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
        buf.putLong(drawing.clientGlobal());
        buf.putLong(drawing.pumpFn());
        buf.putLong(drawing.presentFn());
        return buf.array();
    }

    /**
     * Frames a batch of calls: {@code {u32 len, u8 1, u32 batch, u16 count, calls}}, each call
     * {@code {u64 rva, u8 argc, argc x {u8 kind, i64 value}, u16 dataLen, data}}. The length counts the bytes after it.
     *
     * @param batch the batch id the DLL reports back when the batch has run
     * @param calls the calls, in order
     * @return the framed command
     */
    static byte[] calls(int batch, List<NativeCall> calls) {
        if (calls.size() > 0xffff) {
            throw new IllegalArgumentException("at most 65535 calls a batch, got " + calls.size());
        }
        int body = 1 + 4 + 2;
        for (NativeCall call : calls) {
            body += 8 + 1 + call.args().size() * 9 + 2 + call.data().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_CALLS);
        buf.putInt(batch);
        buf.putShort((short) calls.size());
        for (NativeCall call : calls) {
            buf.putLong(call.rva());
            buf.put((byte) call.args().size());
            for (NativeCall.Arg arg : call.args()) {
                buf.put((byte) arg.kind().ordinal());
                buf.putLong(arg.value());
            }
            byte[] data = call.data();
            buf.putShort((short) data.length);
            buf.put(data);
        }
        return buf.array();
    }

    /**
     * Frames the overlay surface command: {@code {u32 len, u8 2, u32 generation, u16 nameLen, name}}. An empty name
     * stops the drawing. The length counts the bytes after it.
     *
     * @param generation the surface's generation
     * @param name       the file mapping's name (ASCII), or "" to stop
     * @return the framed command
     */
    static byte[] surface(int generation, String name) {
        byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
        int body = 1 + 4 + 2 + ascii.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_SURFACE);
        buf.putInt(generation);
        buf.putShort((short) ascii.length);
        buf.put(ascii);
        return buf.array();
    }

    /**
     * Frames the frame cap command: {@code {u32 len, u8 3, u32 fps}}, 0 for no cap. The length counts the bytes after
     * it.
     *
     * @param fps the frames a second to hold the game to, or 0 to let it run free
     * @return the framed command
     */
    static byte[] frameLimit(int fps) {
        int body = 1 + 4;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_FRAME_LIMIT);
        buf.putInt(Math.max(0, fps));
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
