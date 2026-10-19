package com.kraken.api.config;

/**
 * A varbit's definition (config group 14): which varp it lives in and which bits of it. Its value is
 * {@code (varp >> startBit) & mask(endBit - startBit)}.
 *
 * @param baseVar  the varp holding the varbit
 * @param startBit the lowest bit
 * @param endBit   the highest bit (inclusive)
 */
public record VarBitType(int baseVar, int startBit, int endBit) {

    /**
     * What the client uses for an id with no definition: varp 0, bit 0.
     */
    public static final VarBitType DEFAULT = new VarBitType(0, 0, 0);

    /**
     * This varbit's value within a value of its varp: {@code (varp >> startBit) & mask[endBit - startBit]}, where the
     * client's mask table holds {@code 2^(n+1) - 1} for n = 0..31. A definition whose bit range falls outside that
     * table (none in the game's data) would make the client read past the table; here it yields 0.
     *
     * @param varpValue the value of varp {@link #baseVar}
     * @return the varbit's bits shifted down to bit 0, or 0 for a bit range outside the mask table
     */
    public int extract(int varpValue) {
        int width = endBit - startBit;
        if (width < 0 || width > 31) {
            return 0;
        }
        int mask = width == 31 ? -1 : (1 << width + 1) - 1;
        return varpValue >> startBit & mask;
    }

    /**
     * Decodes a varbit file as the client does: opcode 1 is {@code u16 baseVar, u8 startBit, u8 endBit}; the client
     * skips any other opcode without reading operands, so this does too.
     *
     * @param data the file's bytes, from its first opcode
     * @return the definition (varp 0, bits 0..0 if the file has no opcode 1)
     * @throws ConfigFormatException if the file ends before its end opcode
     */
    public static VarBitType decode(byte[] data) {
        ConfigBuffer buffer = new ConfigBuffer(data);
        int baseVar = 0;
        int startBit = 0;
        int endBit = 0;
        for (int opcode = buffer.g1(); opcode != 0; opcode = buffer.g1()) {
            if (opcode == 1) {
                baseVar = buffer.g2();
                startBit = buffer.g1();
                endBit = buffer.g1();
            }
        }
        return new VarBitType(baseVar, startBit, endBit);
    }
}
