package com.kraken.api.internal;

import com.kraken.api.actor.Actor;
import com.kraken.api.coords.WorldPoint;

/**
 * One end of a projectile's path: a world tile, and the actor the path starts from or aims at, if any. The client
 * uses the actor's current position while it can find the actor, and the tile's centre otherwise.
 *
 * @param tile       the world tile the server sent (a world tile, unlike most {@link MapCoord}s)
 * @param height     how far above the actor or the tile's ground the path starts or ends, in fine height units
 *                   (the client subtracts it from that height, so larger is higher)
 * @param actorType  {@link Actor#INTERACTING_NPC}, {@link Actor#INTERACTING_PLAYER}, or
 *                   {@link Actor#INTERACTING_NONE} for a tile
 * @param actorIndex the NPC or player index, or -1 for a tile
 */
public record ProjectileEndpoint(MapCoord tile, int height, int actorType, int actorIndex) {

    /**
     * Decodes an endpoint as the client stores it. The actor reference is 0 for none, {@code n > 0} for NPC index
     * {@code n - 1}, and {@code n < 0} for player index {@code -n - 1}.
     *
     * @param level  the tile's level
     * @param x      the world tile x
     * @param z      the world tile z
     * @param height the height offset
     * @param actor  the actor reference
     * @return the endpoint
     */
    public static ProjectileEndpoint decode(int level, int x, int z, int height, int actor) {
        MapCoord tile = new MapCoord(level, x, z);
        if (actor > 0) {
            return new ProjectileEndpoint(tile, height, Actor.INTERACTING_NPC, actor - 1);
        }
        if (actor < 0) {
            return new ProjectileEndpoint(tile, height, Actor.INTERACTING_PLAYER, ~actor);
        }
        return new ProjectileEndpoint(tile, height, Actor.INTERACTING_NONE, -1);
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
