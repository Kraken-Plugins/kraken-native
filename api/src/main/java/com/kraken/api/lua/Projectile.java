package com.kraken.api.lua;

/**
 * A projectile in flight (an arrow, a spell, a cannonball…), as one read of the client's projectile list. Not a Lua
 * type: the Lua API can only hide or show every projectile ({@code ClientOp.renderProjectiles}) and ask whether any is
 * shown ({@link ClientOp#areProjectilesRendered}); this is the Java API's own listing, the counterpart of RuneLite's
 * {@code Projectile}.
 *
 * <p>The server sends each projectile with its two {@link Endpoint}s, their heights, its timing and its spot anim. The
 * client keeps it until the cycle passes {@link #endCycle()}. From {@link #startCycle()} on it moves it every cycle
 * towards where the target is now, so a projectile aimed at an actor follows that actor. It has no position until it
 * has moved once ({@link #started()}).
 *
 * <p>A snapshot: re-read the list ({@link ClientOp#getProjectiles}) for fresh positions. Cycles are client cycles
 * (20 ms each), the clock the client runs its animations on.
 *
 * @param id            the projectile's spot anim id (its {@code SpotAnimType}), what RuneLite calls the projectile id
 * @param source        where the projectile comes from
 * @param target        where it is going
 * @param startCycle    the cycle it sets off on; until then the client neither moves nor draws it
 * @param endCycle      the cycle it lands on; the client drops it once the cycle passes this
 * @param slope         its initial climb, in 1/256 of a turn (the client climbs at tan(slope × 2π/256) times the
 *                      horizontal speed, then curves down to the target)
 * @param startDistance how far along the path from the source it appears, in fine units
 * @param started       whether the client has placed it yet (on its first cycle at or after {@code startCycle})
 * @param x             east-west position in top-level scene fine units, 0 until {@code started}
 * @param y             height, more negative is higher; 0 until {@code started}
 * @param z             north-south position in top-level scene fine units, 0 until {@code started}
 * @param yaw           heading, 0-2047 (2048 per turn), the angle the client draws the model at
 * @param pitch         climb angle of the flight path, 0-2047
 * @param hidden        whether {@code renderProjectiles(false)} hid it
 * @param cycle         the client cycle when this was read
 */
public record Projectile(int id, Endpoint source, Endpoint target, int startCycle, int endCycle, int slope,
                         int startDistance, boolean started, double x, double y, double z, int yaw, int pitch,
                         boolean hidden, int cycle) {

    /**
     * The position, truncated to whole fine units as the client draws it.
     *
     * @return the position with its height in {@code y}, or {@link FineCoord#MISSING} before the client has placed it
     */
    public FineCoord position() {
        return started ? new FineCoord((int) x, (int) y, (int) z) : FineCoord.MISSING;
    }

    /**
     * How long until it lands, as of this read.
     *
     * @return {@code endCycle - cycle}, in client cycles (20 ms)
     */
    public int remainingCycles() {
        return endCycle - cycle;
    }

    /**
     * Whether the client draws it, the per-projectile flag {@link ClientOp#areProjectilesRendered} checks.
     *
     * @return true unless it is hidden
     */
    public boolean isRendered() {
        return !hidden;
    }

    /**
     * One end of a projectile's path: a world tile, and the actor the path starts from or aims at, if any. The client
     * uses the actor's current position while it can find the actor, and the tile's centre otherwise.
     *
     * @param tile       the world tile the server sent. Unlike most {@link MapCoord}s from the API it is a world tile,
     *                   not a scene tile; {@link Scene#toScene} converts
     * @param height     how far above the actor or the tile's ground the path starts or ends, in fine height units
     *                   (the client subtracts it from that height, so larger is higher)
     * @param actorType  {@link Entity#TARGET_NPC}, {@link Entity#TARGET_PLAYER}, or {@link Entity#TARGET_NONE} for a tile
     * @param actorIndex the NPC or player index ({@link ClientOp#getNpcObj}, {@link ClientOp#getPlayerObj}), or -1
     *                   for a tile
     */
    public record Endpoint(MapCoord tile, int height, int actorType, int actorIndex) {

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
        static Endpoint decode(int level, int x, int z, int height, int actor) {
            MapCoord tile = new MapCoord(level, x, z);
            if (actor > 0) {
                return new Endpoint(tile, height, Entity.TARGET_NPC, actor - 1);
            }
            if (actor < 0) {
                return new Endpoint(tile, height, Entity.TARGET_PLAYER, ~actor);
            }
            return new Endpoint(tile, height, Entity.TARGET_NONE, -1);
        }

        /**
         * Whether this end is an actor rather than a tile.
         *
         * @return true for an NPC or a player
         */
        public boolean isActor() {
            return actorType != Entity.TARGET_NONE;
        }
    }
}
