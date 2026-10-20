package com.kraken.api.definition;

import com.kraken.api.Client;
import lombok.Value;

/**
 * A varbit's definition (config group 14): which varp it lives in and which bits of it. Its value is
 * {@code (varp >> leastSignificantBit) & mask(mostSignificantBit - leastSignificantBit)}. Get one from
 * {@link Client#getVarbit}.
 */
@Value
public class VarbitComposition {

    /**
     * What the client uses for an id with no definition: varp 0, bit 0.
     */
    public static final VarbitComposition DEFAULT = new VarbitComposition(0, 0, 0);

    /**
     * The varp holding the varbit.
     */
    int index;

    /**
     * The varbit's lowest bit in its varp.
     */
    int leastSignificantBit;

    /**
     * The varbit's highest bit in its varp (inclusive).
     */
    int mostSignificantBit;

    /**
     * This varbit's value within a value of its varp: {@code (varp >> lsb) & mask[msb - lsb]}, where the client's mask
     * table holds {@code 2^(n+1) - 1} for n = 0..31. A definition whose bit range falls outside that table (none in the
     * game's data) would make the client read past the table; here it yields 0.
     *
     * @param varpValue the value of varp {@link #getIndex()}
     * @return the varbit's bits shifted down to bit 0, or 0 for a bit range outside the mask table
     */
    public int extract(int varpValue) {
        int width = mostSignificantBit - leastSignificantBit;
        if (width < 0 || width > 31) {
            return 0;
        }
        int mask = width == 31 ? -1 : (1 << width + 1) - 1;
        return varpValue >> leastSignificantBit & mask;
    }
}
