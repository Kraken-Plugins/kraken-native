package com.kraken.client.callback;

import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.client.callback.NativeProtocol.Drawing;
import com.kraken.client.callback.NativeProtocol.Plan;
import com.kraken.client.callback.NativeProtocol.Slot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(slots, drawing)).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        buf.get(magic);
        assertArrayEquals("KRK4".getBytes(StandardCharsets.US_ASCII), magic);
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
        assertEquals(0, buf.remaining(), "no trailing bytes");
    }

    @Test
    void aHandshakeWithoutDrawingEndsInZeros() {
        ByteBuffer buf = ByteBuffer.wrap(NativeProtocol.handshake(List.of(), Drawing.NONE))
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        assertEquals(0, buf.getShort());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.getLong());
        assertEquals(0, buf.remaining());
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
     * the way {@code kraken_hook.cpp} lays them.
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
