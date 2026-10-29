package com.kraken.client.callback;

import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.mapping.Mapping;
import com.kraken.client.callback.NativeProtocol.Drawing;
import com.kraken.client.callback.NativeProtocol.Plan;
import com.kraken.client.callback.NativeProtocol.Slot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire format {@link NativeProtocol} shares with {@code kraken_hook.dll}: the handshake and commands it builds and
 * the messages it parses, field for field and op for op.
 */
class NativeProtocolTest {

    @Test
    void handshakeHasMagicCountAndEachSlotInOrder() {
        List<Slot> slots = List.of(
                new Slot(0xc44d0L, -1, 0, 1, List.of()),
                new Slot(0xc7b20L, 40, 0, 4, List.of(
                        new Plan(NativeEvent.RDX, NativeProtocol.OP_I32),
                        new Plan(NativeEvent.R8, NativeProtocol.OP_CSTR),
                        new Plan(NativeEvent.R9, NativeProtocol.OP_I32))));
        Drawing drawing = new Drawing(0xe98c18L, 0x918950L, 0xa555c0L);
        byte[] scripts = {9, 8, 7};
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(slots, drawing, scripts))
                .order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        buf.get(magic);
        assertArrayEquals("KRK5".getBytes(StandardCharsets.US_ASCII), magic);
        assertEquals(2, buf.getShort(), "slot count");

        assertEquals(0xc44d0L, buf.getLong());
        assertEquals(-1, buf.getInt(), "idArg slot carries no fixed id");
        assertEquals(0, buf.get(), "idArg register");
        assertEquals(1, buf.get(), "args");
        assertEquals(0, buf.get(), "empty plan");

        assertEquals(0xc7b20L, buf.getLong());
        assertEquals(40, buf.getInt(), "fixed id");
        assertEquals(0, buf.get());
        assertEquals(4, buf.get());
        assertEquals(3, buf.get(), "plan length");
        assertEquals(NativeEvent.RDX, buf.get());
        assertEquals(NativeProtocol.OP_I32, buf.get());
        assertEquals(NativeEvent.R8, buf.get());
        assertEquals(NativeProtocol.OP_CSTR, buf.get());
        assertEquals(NativeEvent.R9, buf.get());
        assertEquals(NativeProtocol.OP_I32, buf.get());
        assertEquals(0xe98c18L, buf.getLong(), "the client pointer's slot");
        assertEquals(0x918950L, buf.getLong(), "the pump");
        assertEquals(0xa555c0L, buf.getLong(), "the present");
        assertEquals(3, buf.getShort(), "the script block's length");
        assertEquals(9, buf.get());
        assertEquals(8, buf.get());
        assertEquals(7, buf.get());
        assertEquals(0, buf.remaining(), "no trailing bytes");
    }

    @Test
    void aHandshakeWithoutDrawingOrScriptsEndsInZeros() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(List.of(), Drawing.NONE, new byte[0]))
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        assertEquals(0, buf.getShort());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getShort(), "no script block");
        assertEquals(0, buf.remaining());
    }

    @Test
    void theScriptBlockFollowsTheMappingInTheDllsOrder() throws IOException {
        Mapping mapping = Mapping.bundled("241.3");
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.scriptLayout(mapping)).order(ByteOrder.LITTLE_ENDIAN);

        for (String function : new String[]{"executeScript", "executeScriptInner", "scriptLookup", "scriptSwapLocals"}) {
            assertEquals(mapping.function(function), buf.getLong(), function);
        }
        assertEquals(mapping.global("scriptRunner"), buf.getLong(), "the runner");
        for (String key : new String[]{"ScriptRunner.intStack", "ScriptRunner.intStackSize", "ScriptRunner.objStack",
                "ScriptRunner.objStackSize", "ScriptRunner.frameCount", "ScriptFrame.script"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        assertEquals(mapping.container("shared_ptr.ptr"), buf.getInt(), "a shared_ptr's pointer");
        for (String key : new String[]{"ClientScript.id", "VarValue.size", "VarValue.type"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String kind : new String[]{"INT", "STRING", "LONG"}) {
            assertEquals(mapping.enumValue("varValueType", kind, -1), buf.get(), kind);
        }
        for (String key : new String[]{"HookReq.argCount", "HookReq.args", "HookReq.source", "HookReq.target",
                "HookReq.op", "HookReq.subOp", "HookReq.mouseX", "HookReq.mouseY", "HookReq.keyCode",
                "HookReq.keyChar", "HookReq.opbase", "IfType.packedId", "IfType.subId"}) {
            assertEquals(mapping.offset(key), buf.getInt(), key);
        }
        for (String key : new String[]{"string.heapFlagByte", "string.heapFlagMask", "string.ssoCapacity"}) {
            assertEquals(mapping.container(key), buf.getInt(), key);
        }
        assertEquals(0, buf.remaining(), "147 bytes, as the DLL reads them");
    }

    @Test
    void aMappingWithoutTheScriptRuntimeHasNoScriptBlock() throws IOException {
        Path file = Files.createTempFile("mapping", ".json");
        try {
            Files.writeString(file, "{\"version\": \"1.0\", \"sha256\": \"00\", \"globals\": {\"client\": \"0x10\"},"
                    + " \"structs\": {}, \"containers\": {}, \"enums\": {}, \"functions\": {}}");
            assertEquals(0, NativeProtocol.scriptLayout(Mapping.load(file)).length);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void scriptCaptureFramesTheFlagsAndTheRootScripts() {
        int[] none = {};
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.scriptCapture(true, false, none)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, buf.getInt(), "the type byte, the flags and the count");
        assertEquals(NativeProtocol.COMMAND_SCRIPTS, buf.get());
        assertEquals(1, buf.get(), "starts only");
        assertEquals(0, buf.get(), "no root scripts");
        assertEquals(0, buf.remaining());
        assertEquals(2, NativeProtocol.scriptCapture(false, true, none)[5], "returns only");
        assertEquals(3, NativeProtocol.scriptCapture(true, true, none)[5]);
        assertEquals(0, NativeProtocol.scriptCapture(false, false, none)[5]);

        byte[] xpDrops = NativeProtocol.scriptCapture(false, false, new int[]{2091});
        buf = ByteBuffer.wrap(xpDrops).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(7, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_SCRIPTS, buf.get());
        assertEquals(4, buf.get(), "the listed root scripts only");
        assertEquals(1, buf.get());
        assertEquals(2091, buf.getInt());
        assertEquals(0, buf.remaining());

        int[] many = IntStream.range(0, 20).toArray();
        assertEquals(NativeProtocol.MAX_SCRIPT_ROOTS, NativeProtocol.scriptCapture(false, false, many)[6]);
    }

    @Test
    void parsesAGosubsStacksIntoArraysAsLongAsTheStacks() {
        ByteBuffer buf = scriptHead(12, 1, 1004, 2);
        buf.putInt(5).putShort((short) 2).putInt(30).putInt(40);
        buf.putInt(3).putShort((short) 2);
        buf.put((byte) NativeProtocol.VALUE_STRING).putShort((short) 7).put("Draynor".getBytes(StandardCharsets.UTF_8));
        buf.put((byte) NativeProtocol.VALUE_OTHER);
        buf.put((byte) 0);
        byte[] body = java.util.Arrays.copyOf(buf.array(), buf.position());

        assertEquals(NativeProtocol.SCRIPT_EVENT_ID, NativeProtocol.eventId(body));
        assertEquals(12, NativeProtocol.seq(body));
        ScriptRun run = NativeProtocol.parseScript(body);
        assertEquals(ScriptRun.Kind.PRE_GOSUB, run.kind());
        assertEquals(1004, run.scriptId());
        assertEquals(2, run.depth());
        assertArrayEquals(new int[]{0, 0, 0, 30, 40}, run.stack().ints(), "the captured top at the end");
        assertArrayEquals(new Object[]{null, "Draynor", null}, run.stack().objects());
        assertNull(run.request());
    }

    @Test
    void parsesARootRunsRequest() {
        ByteBuffer buf = scriptHead(1, 0, 286, 0);
        buf.putInt(0).putShort((short) 0);
        buf.putInt(0).putShort((short) 0);
        buf.put((byte) 1);
        for (int field : new int[]{149 << 16, 4, -1, -1, 2, 0, 310, 220, -1, -1}) {
            buf.putInt(field);
        }
        buf.putShort((short) 5).put("Wield".getBytes(StandardCharsets.UTF_8));
        buf.putShort((short) 3);
        buf.put((byte) NativeProtocol.VALUE_INT).putInt(286);
        buf.put((byte) NativeProtocol.VALUE_INT).putInt(-2147483645);
        buf.put((byte) NativeProtocol.VALUE_LONG).putLong(1L << 40);
        ScriptRun run = NativeProtocol.parseScript(java.util.Arrays.copyOf(buf.array(), buf.position()));

        assertEquals(ScriptRun.Kind.PRE_ROOT, run.kind());
        assertEquals(0, run.stack().ints().length);
        ScriptRequest request = run.request();
        assertEquals(149 << 16, request.sourceId());
        assertEquals(4, request.sourceIndex());
        assertEquals(-1, request.targetId());
        assertEquals(2, request.op());
        assertEquals(0, request.subOp());
        assertEquals(310, request.mouseX());
        assertEquals(220, request.mouseY());
        assertEquals("Wield", request.opbase());
        assertArrayEquals(new Object[]{286, -2147483645, 1L << 40}, request.arguments());
    }

    @Test
    void aStackSizeThatIsABadReadKeepsJustTheCapturedEntries() {
        ByteBuffer buf = scriptHead(1, 2, 7, 0);
        buf.putInt(-5).putShort((short) 1).putInt(9);
        buf.putInt(1_000_000).putShort((short) 0);
        buf.put((byte) 0);
        ScriptRun run = NativeProtocol.parseScript(java.util.Arrays.copyOf(buf.array(), buf.position()));
        assertEquals(ScriptRun.Kind.POST, run.kind());
        assertArrayEquals(new int[]{9}, run.stack().ints());
        assertEquals(0, run.stack().objects().length);
        assertTrue(run.request() == null);
    }

    /**
     * Starts a script message as the DLL lays it out: the head with {@code {kind, id, depth, 0}} and no plan values.
     *
     * @param seq   the sequence number
     * @param kind  0 a root script starting, 1 a gosub, 2 a return
     * @param id    the script's id
     * @param depth its depth
     * @return the buffer, positioned after the head
     */
    private static ByteBuffer scriptHead(int seq, int kind, int id, int depth) {
        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seq).putInt(NativeProtocol.SCRIPT_EVENT_ID);
        buf.putLong(kind).putLong(id).putLong(depth).putLong(0);
        buf.put((byte) 0);
        return buf;
    }

    @Test
    void callsFrameEachCallWithItsArgumentsAndData() {
        NativeCall configure = NativeCall.to(0x3ad8c0L).imm(0).imm(7).imm(31).data(new byte[]{1, 2, 3}).build();
        NativeCall clear = NativeCall.to(0x1b9550L).client(0x89680).imm(7).imm(-1).build();
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.calls(42, List.of(configure, clear)))
                .order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(buf.capacity() - 4, buf.getInt(), "the length counts the type and the body");
        assertEquals(NativeProtocol.COMMAND_CALLS, buf.get());
        assertEquals(42, buf.getInt(), "batch id");
        assertEquals(2, buf.getShort(), "call count");

        assertEquals(0x3ad8c0L, buf.getLong());
        assertEquals(4, buf.get(), "argc");
        long[][] args = {{0, 0}, {0, 7}, {0, 31}, {2, 0}};
        for (long[] arg : args) {
            assertEquals(arg[0], buf.get(), "kind");
            assertEquals(arg[1], buf.getLong(), "value");
        }
        assertEquals(3, buf.getShort(), "data length");
        assertEquals(1, buf.get());
        assertEquals(2, buf.get());
        assertEquals(3, buf.get());

        assertEquals(0x1b9550L, buf.getLong());
        assertEquals(3, buf.get());
        assertEquals(1, buf.get(), "CLIENT");
        assertEquals(0x89680, buf.getLong());
        assertEquals(0, buf.get());
        assertEquals(7, buf.getLong());
        assertEquals(0, buf.get());
        assertEquals(-1, buf.getLong(), "negative values go as two's complement");
        assertEquals(0, buf.getShort(), "no data");
        assertEquals(0, buf.remaining());
    }

    @Test
    void scratchAndIndirectArgumentsGoAsTheirKindAndPackedValue() {
        NativeCall create = NativeCall.to(0x6795c0L).scratch(0).imm(7).indirect(0x31a0, 0xa08).build();
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.calls(1, List.of(create))).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4 + 1 + 4 + 2 + 8);
        assertEquals(3, buf.get(), "argc");
        assertEquals(3, buf.get(), "SCRATCH");
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.get(), "IMM");
        assertEquals(7, buf.getLong());
        assertEquals(4, buf.get(), "INDIRECT");
        assertEquals(0xa08L << 32 | 0x31a0, buf.getLong(), "pointer offset low, addend high");
        assertEquals(0, buf.getShort(), "no data");
    }

    @Test
    void surfaceFramesTheGenerationAndName() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.surface(3, "Local\\KrakenOverlay-12-3"))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(buf.capacity() - 4, buf.getInt());
        assertEquals(NativeProtocol.COMMAND_SURFACE, buf.get());
        assertEquals(3, buf.getInt());
        int length = buf.getShort();
        byte[] name = new byte[length];
        buf.get(name);
        assertEquals("Local\\KrakenOverlay-12-3", new String(name, StandardCharsets.US_ASCII));
        assertEquals(0, buf.remaining());

        ByteBuffer stop = ByteBuffer.wrap(NativeProtocol.surface(0, "")).order(ByteOrder.LITTLE_ENDIAN);
        stop.position(9);
        assertEquals(0, stop.getShort(), "an empty name stops the drawing");
    }

    @Test
    void frameLimitFramesTheRate() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.frameLimit(20)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5, buf.getInt(), "the type byte and a u32");
        assertEquals(NativeProtocol.COMMAND_FRAME_LIMIT, buf.get());
        assertEquals(20, buf.getInt());
        assertEquals(0, buf.remaining());

        ByteBuffer off = ByteBuffer.wrap(NativeProtocol.frameLimit(-3)).order(ByteOrder.LITTLE_ENDIAN);
        off.position(5);
        assertEquals(0, off.getInt(), "a negative rate is no cap");
    }

    @Test
    void parsesIdRegistersAndSequenceWithNoValues() {
        byte[] body = message(7, 44, new long[]{0xAA, 0xBB, 0xCC, 0xDD}, builder -> {
        });
        NativeEvent event = NativeProtocol.parse(body, id -> id == 44 ? "ON_GAME_TICK" : null);

        assertEquals("ON_GAME_TICK", event.name());
        assertEquals(44, event.id());
        assertEquals(7, event.seq());
        assertEquals(0xAA, event.reg(NativeEvent.RCX));
        assertEquals(0xDD, event.reg(NativeEvent.R9));
        assertEquals(Map.of(), event.values());
    }

    @Test
    void parsesEachValueOp() {
        byte[] body = message(1, 40, new long[4], builder -> {
            builder.i32(NativeEvent.RDX, 0x12345);
            builder.cstr(NativeEvent.R8, "Oak");
            builder.i32(NativeEvent.R9, 5);
        });
        NativeEvent event = NativeProtocol.parse(body, id -> "ON_LOC_LOAD");

        assertEquals(0x12345, event.i32(NativeEvent.RDX));
        assertEquals("Oak", event.str(NativeEvent.R8));
        assertEquals(5, event.i32(NativeEvent.R9));
    }

    @Test
    void parsesIntPairAndRaw() {
        byte[] body = message(2, 46, new long[4], builder -> {
            builder.i32x2(NativeEvent.RDX, 100, 7);
            builder.raw(NativeEvent.R8, 0x1_0000_0003L);
        });
        NativeEvent event = NativeProtocol.parse(body, id -> "ON_VARBIT_UPDATE");

        assertArrayEquals(new int[]{100, 7}, event.i32x2(NativeEvent.RDX));
        assertEquals(0x1_0000_0003L, event.i64(NativeEvent.R8));
        assertEquals(3, event.i32(NativeEvent.R8), "the low int of a raw register");
    }

    @Test
    void unknownIdLeavesTheNameNull() {
        byte[] body = message(0, 999, new long[4], builder -> {
        });
        NativeEvent event = NativeProtocol.parse(body, id -> null);
        assertNull(event.name());
        assertEquals(999, event.id());
    }

    /**
     * Builds a message body in the DLL's format, so {@link NativeProtocol#parse} can be tested against bytes laid out
     * the way the DLL lays them ({@code src/core/ring.cpp} and {@code src/raises.cpp}).
     *
     * @param seq     the sequence number
     * @param eventId the event id
     * @param regs    the four argument registers
     * @param values  appends the captured values
     * @return the message body (the bytes after the length frame)
     */
    private static byte[] message(int seq, int eventId, long[] regs, java.util.function.Consumer<Values> values) {
        ByteBuffer buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seq);
        buf.putInt(eventId);
        for (int i = 0; i < 4; i++) {
            buf.putLong(regs[i]);
        }
        int countAt = buf.position();
        buf.put((byte) 0);
        Values appender = new Values(buf);
        values.accept(appender);
        buf.put(countAt, (byte) appender.count);
        return java.util.Arrays.copyOf(buf.array(), buf.position());
    }

    /**
     * Appends {@code {arg, op, payload}} values to a message body and counts them.
     */
    private static final class Values {
        private final ByteBuffer buf;
        private int count;

        Values(ByteBuffer buf) {
            this.buf = buf;
        }

        void raw(int arg, long value) {
            head(arg, NativeProtocol.OP_RAW);
            buf.putLong(value);
        }

        void i32(int arg, int value) {
            head(arg, NativeProtocol.OP_I32);
            buf.putInt(value);
        }

        void i32x2(int arg, int a, int b) {
            head(arg, NativeProtocol.OP_I32X2);
            buf.putInt(a);
            buf.putInt(b);
        }

        void cstr(int arg, String value) {
            head(arg, NativeProtocol.OP_CSTR);
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            buf.put((byte) bytes.length);
            buf.put(bytes);
        }

        private void head(int arg, int op) {
            buf.put((byte) arg);
            buf.put((byte) op);
            count++;
        }
    }
}
