package com.kraken.client.callback;

import com.kraken.api.internal.ScriptStack;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.mapping.Mapping;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The wire format between {@link NativeEventServer} and {@code kraken_hook.dll} (protocol v5, little-endian). Java
 * sends one handshake naming the raise functions to hook, their event ids and a capture plan per slot, then the
 * functions the drawing needs, then the script runtime's functions and layout; the DLL sends one message per raise
 * with the id, the argument registers and the captured values, one per script event it was asked for, and status
 * messages with reserved ids. After the handshake Java sends commands: batches of calls for the game thread, the
 * overlay surface to draw, the frame cap and which script events to send. Every field and op code here matches the
 * constants in {@code kraken_hook.cpp}; {@code docs/in-game-drawing.md} and {@code docs/event-hooks-design.md}
 * describe the format.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeProtocol {

    /**
     * Handshake magic: identifies protocol v5.
     */
    static final byte[] MAGIC = {'K', 'R', 'K', '5'};

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
     * A reserved event id for a script event: a script starting or returning, with the top of the stacks (see
     * {@link #parseScript}). It goes through the DLL's ring like a raise.
     */
    static final int SCRIPT_EVENT_ID = -6;

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
     * The STATUS feature bit for the script hooks: the DLL sends script events while {@link #COMMAND_SCRIPTS} asks.
     */
    static final long FEATURE_SCRIPTS = 8;

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
     * Command type: which script events to send.
     */
    static final int COMMAND_SCRIPTS = 4;

    /**
     * How a script message encodes a value: a kind byte, then an int32, a u16 length and UTF-8 bytes, an int64, or
     * nothing.
     */
    static final int VALUE_OTHER = 0;
    static final int VALUE_INT = 1;
    static final int VALUE_STRING = 2;
    static final int VALUE_LONG = 3;

    /**
     * How many entries the script engine's stacks hold; a reported size beyond it is a bad read.
     */
    static final int MAX_STACK = 1000;

    /**
     * The mapping keys the handshake's script block is built from, in the order the DLL reads them (after the four
     * functions and the runner): the runner's stacks and frame count, a frame's script slot, a shared_ptr's pointer,
     * a script's id and the VarValue's size and kind byte.
     */
    private static final String[] SCRIPT_OFFSETS = {"ScriptRunner.intStack", "ScriptRunner.intStackSize",
            "ScriptRunner.objStack", "ScriptRunner.objStackSize", "ScriptRunner.frameCount", "ScriptFrame.script",
            "container:shared_ptr.ptr", "ClientScript.id", "VarValue.size", "VarValue.type"};

    /**
     * The script block's request fields, a component's id and index, and the eastl string layout, in order.
     */
    private static final String[] SCRIPT_FIELDS = {"HookReq.argCount", "HookReq.args", "HookReq.source",
            "HookReq.target", "HookReq.op", "HookReq.subOp", "HookReq.mouseX", "HookReq.mouseY", "HookReq.keyCode",
            "HookReq.keyChar", "HookReq.opbase", "IfType.packedId", "IfType.subId", "container:string.heapFlagByte",
            "container:string.heapFlagMask", "container:string.ssoCapacity"};

    /**
     * The four script functions the DLL hooks, in order.
     */
    private static final String[] SCRIPT_FUNCTIONS = {"executeScript", "executeScriptInner", "scriptLookup",
            "scriptSwapLocals"};

    /**
     * The VarValue kinds the script block names, in order; a kind the mapping lacks is sent as 0xff.
     */
    private static final String[] SCRIPT_KINDS = {"INT", "STRING", "LONG"};

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
     * the drawing functions, then a u16 length and the script block.
     *
     * @param slots   the slots to install
     * @param drawing the client pointer, the pump and the present function
     * @param scripts the script block ({@link #scriptLayout}), empty for no script hooks
     * @return the handshake bytes
     */
    static byte[] handshake(List<Slot> slots, Drawing drawing, byte[] scripts) {
        int size = MAGIC.length + 2 + 3 * 8 + 2 + scripts.length;
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
        buf.putShort((short) scripts.length);
        buf.put(scripts);
        return buf.array();
    }

    /**
     * Builds the handshake's script block from a mapping: the RVAs of the four script functions and of the runner
     * ({@code u64} each), the offsets of {@link #SCRIPT_OFFSETS} ({@code u32} each), the VarValue kinds INT, STRING
     * and LONG ({@code u8} each), then the offsets of {@link #SCRIPT_FIELDS} ({@code u32} each).
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks a key it needs (no script hooks)
     */
    static byte[] scriptLayout(Mapping mapping) {
        if (!mapping.hasGlobal("scriptRunner")) {
            return new byte[0];
        }
        for (String function : SCRIPT_FUNCTIONS) {
            if (!mapping.hasFunction(function)) {
                return new byte[0];
            }
        }
        int[] offsets = new int[SCRIPT_OFFSETS.length];
        int[] fields = new int[SCRIPT_FIELDS.length];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = layoutValue(mapping, SCRIPT_OFFSETS[i]);
        }
        for (int i = 0; i < fields.length; i++) {
            fields[i] = layoutValue(mapping, SCRIPT_FIELDS[i]);
        }
        if (Arrays.stream(offsets).anyMatch(v -> v < 0) || Arrays.stream(fields).anyMatch(v -> v < 0)) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate((SCRIPT_FUNCTIONS.length + 1) * 8 + offsets.length * 4
                + SCRIPT_KINDS.length + fields.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (String function : SCRIPT_FUNCTIONS) {
            buf.putLong(mapping.function(function));
        }
        buf.putLong(mapping.global("scriptRunner"));
        for (int offset : offsets) {
            buf.putInt(offset);
        }
        for (String kind : SCRIPT_KINDS) {
            buf.put((byte) mapping.enumValue("varValueType", kind, 0xff));
        }
        for (int field : fields) {
            buf.putInt(field);
        }
        return buf.array();
    }

    /**
     * One value of the script block: a struct field, or a container layout value for a {@code container:} key.
     *
     * @param mapping the mapping
     * @param key     the key
     * @return the value, or -1 if the mapping lacks it
     */
    private static int layoutValue(Mapping mapping, String key) {
        if (key.startsWith("container:")) {
            try {
                return mapping.container(key.substring("container:".length()));
            } catch (RuntimeException e) {
                return -1;
            }
        }
        return mapping.offsetOr(key, -1);
    }

    /**
     * Frames the script events command: {@code {u32 len, u8 4, u8 flags}}, bit 0 asking for scripts starting and bit 1
     * for scripts returning. The length counts the bytes after it.
     *
     * @param pre  whether to send scripts starting
     * @param post whether to send scripts returning
     * @return the framed command
     */
    static byte[] scriptCapture(boolean pre, boolean post) {
        int body = 1 + 1;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_SCRIPTS);
        buf.put((byte) ((pre ? 1 : 0) | (post ? 2 : 0)));
        return buf.array();
    }

    /**
     * Frames a batch of calls: {@code {u32 len, u8 1, u32 batch, u16 count, calls}}, each call
     * {@code {u64 rva, u8 argc, argc x {u8 kind, i64 value}, u16 dataLen, data}}, the kind being the ordinal of the
     * argument's {@link NativeCall.Kind}. The length counts the bytes after it.
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
     * A message body's event id, without parsing the rest.
     *
     * @param body the message body
     * @return the event or reserved id
     */
    static int eventId(byte[] body) {
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
    }

    /**
     * A message body's sequence number, without parsing the rest.
     *
     * @param body the message body
     * @return the sequence number
     */
    static long seq(byte[] body) {
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xffffffffL;
    }

    /**
     * Parses a script event's body: the usual head, whose registers are {@code {kind, script id, depth, 0}} (kind 0 a
     * root script starting, 1 a gosub, 2 a return), no plan values, then the int stack's size, a u16 count and that
     * many ints from the top of the stack (oldest first), the object stack's size and a run of values from its top,
     * then a byte saying whether a request follows: the source and target components' ids and indices, the op,
     * sub-op, mouse x and y, key code and key char ({@code i32} each), the base op name (a u16 length and UTF-8
     * bytes) and a run of values, the listener's arguments. A run of values is a u16 count, then each a kind byte
     * ({@link #VALUE_INT}…) and its payload. The stacks come back as long as their sizes, the entries below the
     * captured top left 0 or null.
     *
     * @param body the message body
     * @return the script event
     */
    static ScriptRun parseScript(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8);
        int kind = (int) buf.getLong();
        int scriptId = (int) buf.getLong();
        int depth = (int) buf.getLong();
        buf.getLong();
        buf.get();

        int intSize = buf.getInt();
        int[] top = new int[buf.getShort() & 0xffff];
        for (int i = 0; i < top.length; i++) {
            top[i] = buf.getInt();
        }
        int[] ints = new int[stackLength(intSize, top.length)];
        System.arraycopy(top, 0, ints, ints.length - top.length, top.length);

        int objectSize = buf.getInt();
        Object[] topObjects = values(buf);
        Object[] objects = new Object[stackLength(objectSize, topObjects.length)];
        System.arraycopy(topObjects, 0, objects, objects.length - topObjects.length, topObjects.length);

        ScriptRequest request = null;
        if (buf.get() != 0) {
            int[] fields = new int[10];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = buf.getInt();
            }
            String opbase = string(buf);
            Object[] arguments = values(buf);
            request = new ScriptRequest(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                    fields[7], fields[8], fields[9], opbase, arguments);
        }
        ScriptRun.Kind runKind = switch (kind) {
            case 0 -> ScriptRun.Kind.PRE_ROOT;
            case 1 -> ScriptRun.Kind.PRE_GOSUB;
            default -> ScriptRun.Kind.POST;
        };
        return new ScriptRun(runKind, scriptId, depth, new ScriptStack(ints, objects), request);
    }

    /**
     * How long a captured stack's array is: its reported size, unless that is a bad read or less than what came.
     *
     * @param size     the size the DLL read
     * @param captured how many entries it sent
     * @return the array length
     */
    private static int stackLength(int size, int captured) {
        return size >= captured && size <= MAX_STACK ? size : captured;
    }

    /**
     * Reads a run of values: a u16 count, then each a kind byte and its payload.
     *
     * @param buf the message, at the run
     * @return the values: {@code Integer}s, {@code String}s and {@code Long}s, null for other kinds
     */
    private static Object[] values(ByteBuffer buf) {
        Object[] values = new Object[buf.getShort() & 0xffff];
        for (int i = 0; i < values.length; i++) {
            values[i] = switch (buf.get()) {
                case VALUE_INT -> buf.getInt();
                case VALUE_STRING -> string(buf);
                case VALUE_LONG -> buf.getLong();
                default -> null;
            };
        }
        return values;
    }

    /**
     * Reads a u16 length and that many UTF-8 bytes.
     *
     * @param buf the message, at the string
     * @return the string
     */
    private static String string(ByteBuffer buf) {
        byte[] bytes = new byte[buf.getShort() & 0xffff];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
