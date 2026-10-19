package com.kraken.api.game;

/**
 * A tile position, mirroring the Lua {@code osrs.MapCoord}: {@code level} is the plane (0-3) and {@code x}/{@code z}
 * are tile coordinates ({@code z} points north; RuneLite calls this axis y).
 *
 * <p>Like the client's, these are <em>scene</em> tiles: relative to the loaded map's base, which the client subtracts
 * from the server's world coordinates when it places an actor (a normal scene is 104×104 tiles). Add
 * {@link Scene#getBase()} for the world tile the wiki and RuneLite use ({@link Scene#toWorld}).
 *
 * @param level the plane, 0-3
 * @param x     the scene tile x (east)
 * @param z     the scene tile z (north)
 */
public record MapCoord(int level, int x, int z) {

    /**
     * Chebyshev (king-move) tile distance to another coordinate on the same plane.
     *
     * @param other the other tile
     * @return the larger of the x and z distances
     */
    public int distanceTo(MapCoord other) {
        return Math.max(Math.abs(x - other.x), Math.abs(z - other.z));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ", " + level + ")";
    }
}
