package com.kraken.api.game;

/**
 * A tile position, mirroring the Lua {@code osrs.MapCoord}: {@code level} is the plane (0-3) and
 * {@code x}/{@code z} are world tile coordinates ({@code z} points north; RuneLite calls this axis y).
 */
public record MapCoord(int level, int x, int z) {

    /** Chebyshev (king-move) tile distance to another coordinate on the same plane. */
    public int distanceTo(MapCoord other) {
        return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ", " + level + ")";
    }
}
