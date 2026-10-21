package com.kraken.api.internal;

import com.kraken.api.internal.reader.ProjectileReader;

/**
 * A scene tile as the readers return it: {@code level} is the plane (0-3) and {@code x}/{@code z} are tile coordinates,
 * with {@code z} pointing north.
 *
 * <p>Like the client's, these are <em>scene</em> tiles: relative to the loaded map's base, which the client subtracts
 * from the server's world coordinates when it places an actor (a normal scene is 104×104 tiles). The public API turns
 * them into {@code WorldPoint}s by adding the base. A projectile's endpoints are the exception: the server sends world
 * tiles, and {@link ProjectileReader} keeps them as such.
 *
 * @param level the plane, 0-3
 * @param x     the tile x (east)
 * @param z     the tile z (north)
 */
public record MapCoord(int level, int x, int z) {

    @Override
    public String toString() {
        return "(" + x + ", " + z + ", " + level + ")";
    }
}
