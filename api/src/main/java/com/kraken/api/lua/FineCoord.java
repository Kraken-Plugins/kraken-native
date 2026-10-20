package com.kraken.api.lua;

/**
 * A position in fine units, mirroring the Lua {@code osrs.FineCoord}: {@code x}/{@code z} are 1/128 of a tile (the
 * same space as {@link MapCoord}'s tiles, times 128) and {@code y} is the ground height there. Heights follow the
 * game's convention: more negative is higher.
 *
 * <p>The {@code ClientOp} functions return {@link #MISSING} ({-1, -1, -1}) when the entity or its world isn't loaded,
 * as the client does.
 *
 * @param x east-west position in 1/128 tiles
 * @param y ground height at the position (more negative is higher)
 * @param z north-south position in 1/128 tiles
 */
public record FineCoord(int x, int y, int z) {

    /**
     * What the client returns when there's nothing to report.
     */
    public static final FineCoord MISSING = new FineCoord(-1, -1, -1);

    /**
     * Fine units per tile.
     */
    public static final int TILE = 128;

    /**
     * Whether this is {@link #MISSING}.
     *
     * @return true if all three values are -1
     */
    public boolean isMissing() {
        return equals(MISSING);
    }

    /**
     * Mirrors {@code getMapCoordinate}: the tile this point is on ({@code x >> 7}, {@code z >> 7}). A FineCoord doesn't
     * carry a plane, so pass the one you want in the result.
     *
     * @param level the plane (0-3) to put in the result
     * @return the tile under this point on {@code level}
     */
    public MapCoord getMapCoordinate(int level) {
        return new MapCoord(level, x >> 7, z >> 7);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
