package com.kraken.api.internal.invoke;

import com.kraken.api.internal.invoke.NativeCall.Arg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NativeCall}: the builder's arguments and data block, and the limits the DLL's format sets.
 */
class NativeCallTest {

    @Test
    void dataArgumentsPointAtAlignedCopiesInOneBlock() {
        NativeCall call = NativeCall.to(0x1000).client(0x89680).data(new byte[]{1, 2, 3}).imm(-5)
                .data(new byte[]{9}).build();
        assertEquals(List.of(Arg.client(0x89680), Arg.data(0), Arg.imm(-5), Arg.data(8)), call.args(),
                "the second block starts 8-aligned");
        assertArrayEquals(new byte[]{1, 2, 3, 0, 0, 0, 0, 0, 9}, call.data());
    }

    @Test
    void scratchOffsetsStayInTheBlockAndIndirectPacksBothOffsets() {
        assertEquals(new Arg(NativeCall.Kind.SCRATCH, 8), Arg.scratch(8));
        assertThrows(IllegalArgumentException.class, () -> Arg.scratch(NativeCall.SCRATCH_SIZE));
        assertThrows(IllegalArgumentException.class, () -> Arg.scratch(-1));
        Arg indirect = Arg.indirect(0x31a0, -8);
        assertEquals(NativeCall.Kind.INDIRECT, indirect.kind());
        assertEquals(0x31a0, indirect.value() & 0xffffffffL, "the pointer field's offset in the low word");
        assertEquals(-8, (int) (indirect.value() >> 32), "the signed addend in the high word");
        assertEquals(List.of(Arg.scratch(0), Arg.indirect(1, 2)),
                NativeCall.to(1).scratch(0).indirect(1, 2).build().args());
    }

    @Test
    void callsAreValues() {
        NativeCall a = NativeCall.to(1).imm(2).data(new byte[]{3}).build();
        NativeCall b = NativeCall.to(1).imm(2).data(new byte[]{3}).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, NativeCall.to(1).imm(2).data(new byte[]{4}).build());
        a.data()[0] = 99;
        assertEquals(b, a, "data() hands out a copy");
    }

    @Test
    void atMostEightArguments() {
        NativeCall.Builder builder = NativeCall.to(1);
        for (int i = 0; i < NativeCall.MAX_ARGS; i++) {
            builder.imm(i);
        }
        builder.build();
        builder.imm(8);
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
