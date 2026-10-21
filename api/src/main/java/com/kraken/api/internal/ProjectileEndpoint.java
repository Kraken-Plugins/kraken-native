package com.kraken.api.internal;

import com.kraken.api.actor.Actor;
import com.kraken.api.coords.WorldPoint;

/**
 * One end of a projectile's path: a world tile, and the actor the path starts from or aims at, if any. The client
 * uses the actor's current position while it can find the actor, and the tile's centre otherwise, moved by the
 * endpoint's offsets.
 *
 * @param tile       the world tile the server sent (a world tile, unlike most {@link MapCoord}s)
 * @param height     how far above the actor or the tile's ground the path starts or ends, in fine height units
 *                   (the client subtracts it from that height, so larger is higher)
 * @param actorType  {@link Actor#INTERACTING_NPC}, {@link Actor#INTERACTING_PLAYER}, or
 *                   {@link Actor#INTERACTING_NONE} for a tile
 * @param actorIndex the NPC or player index, or -1 for a tile
 * @param offsetX    how far east of the actor or the tile's centre the path starts or ends, in local units (only a
 *                   keyed projectile has one; 0 otherwise)
 * @param offsetZ    how far north of it, in local units (0 except for a keyed projectile)
 */
public record ProjectileEndpoint(MapCoord tile, int height, int actorType, int actorIndex, int offsetX, int offsetZ) {

    /**
     * An endpoint without offsets, as a projectile from the client's list has.
     *
     * @param tile       the world tile
     * @param height     the height offset
     * @param actorType  the actor's type, or {@link Actor#INTERACTING_NONE}
     * @param actorIndex the actor's index, or -1
     */
    public ProjectileEndpoint(MapCoord tile, int height, int actorType, int actorIndex) {
        this(tile, height, actorType, actorIndex, 0, 0);
    }

    /**
     * Decodes an endpoint of a projectile from the client's list. The actor reference is 0 for none, {@code n > 0} for
     * NPC index {@code n - 1}, and {@code n < 0} for player index {@code -n - 1}.
     *
     * @param level  the tile's level
     * @param x      the world tile x
     * @param z      the world tile z
     * @param height the height offset
     * @param actor  the actor reference
     * @return the endpoint
     */
    public static ProjectileEndpoint decode(int level, int x, int z, int height, int actor) {
        return decode(level, x, z, 0, height, 0, actor);
    }

    /**
     * Decodes an endpoint of a keyed projectile, which adds an east and a north offset to the point it aims at. The
     * actor reference is encoded as in {@link #decode(int, int, int, int, int)}.
     *
     * @param level   the tile's level
     * @param x       the world tile x
     * @param z       the world tile z
     * @param offsetX the east offset, in local units
     * @param height  the height offset
     * @param offsetZ the north offset, in local units
     * @param actor   the actor reference
     * @return the endpoint
     */
    public static ProjectileEndpoint decode(int level, int x, int z, int offsetX, int height, int offsetZ, int actor) {
        MapCoord tile = new MapCoord(level, x, z);
        if (actor > 0) {
            return new ProjectileEndpoint(tile, height, Actor.INTERACTING_NPC, actor - 1, offsetX, offsetZ);
        }
        if (actor < 0) {
            return new ProjectileEndpoint(tile, height, Actor.INTERACTING_PLAYER, ~actor, offsetX, offsetZ);
        }
        return new ProjectileEndpoint(tile, height, Actor.INTERACTING_NONE, -1, offsetX, offsetZ);
    }

    /**
     * The endpoint's tile as a world point.
     *
     * @return the world tile
     */
    public WorldPoint point() {
        return new WorldPoint(tile.x(), tile.z(), tile.level());
    }
}
