package com.kraken.api;

import com.kraken.api.coords.WorldPoint;

/**
 * A projectile in flight (an arrow, a spell, a cannonball…), as RuneLite's {@code Projectile}. Get them from
 * {@link Client#getProjectiles()}.
 *
 * <p>A snapshot of one read of the client's projectile list: its position and angles are as of that read, so read the
 * list again for fresh ones. {@link #getRemainingCycles()} and the actor getters read the game when called. Cycles are
 * client cycles (20 ms), {@link Client#getGameCycle()}'s clock.
 *
 * <p>RuneLite's deprecated getters ({@code getInteracting}, {@code getTarget}, {@code getX1}, {@code getY1},
 * {@code getFloor}, {@code getHeight}) are left out, as are the animation getters and {@code setEndCycle}.
 */
public interface Projectile extends Renderable {

    /**
     * The projectile's id: its spot anim.
     *
     * @return the spot anim id, as in {@code gameval.SpotanimID}
     */
    int getId();

    /**
     * The level the projectile starts on.
     *
     * @return the source tile's plane
     */
    int getSourceLevel();

    /**
     * The tile the projectile starts at, as the server sent it.
     *
     * @return the source world tile
     */
    WorldPoint getSourcePoint();

    /**
     * The actor the projectile starts at, looked up now.
     *
     * @return the NPC or player, or null for a tile, or if the actor isn't loaded in the top-level world
     */
    Actor getSourceActor();

    /**
     * The level the projectile ends on.
     *
     * @return the target tile's plane
     */
    int getTargetLevel();

    /**
     * The tile the projectile is aimed at, as the server sent it. For a projectile aimed at an actor, the client
     * follows the actor instead.
     *
     * @return the target world tile
     */
    WorldPoint getTargetPoint();

    /**
     * The actor the projectile is aimed at, looked up now.
     *
     * @return the NPC or player, or null for a tile (an area attack, say), or if the actor isn't loaded
     */
    Actor getTargetActor();

    /**
     * How high above the source the projectile starts.
     *
     * @return the height offset in local height units (larger is higher)
     */
    int getStartHeight();

    /**
     * How high above the target the projectile ends.
     *
     * @return the height offset in local height units (larger is higher)
     */
    int getEndHeight();

    /**
     * The cycle the projectile sets off on.
     *
     * @return the start cycle
     */
    int getStartCycle();

    /**
     * The cycle the projectile lands on; the client drops it once the cycle passes this.
     *
     * @return the end cycle
     */
    int getEndCycle();

    /**
     * How long until the projectile lands, as of now (one read of the cycle counter).
     *
     * @return {@link #getEndCycle()} less the current cycle, in client cycles
     */
    int getRemainingCycles();

    /**
     * How steeply the projectile climbs at the start: larger slopes arc more.
     *
     * @return the slope
     */
    int getSlope();

    /**
     * How far along its path from the source the projectile appears.
     *
     * @return the distance in local units
     */
    int getStartPos();

    /**
     * The projectile's x as of the read.
     *
     * @return the local x; 0 until the client has placed it ({@link #isStarted()})
     */
    double getX();

    /**
     * The projectile's y (north) as of the read.
     *
     * @return the local y; 0 until the client has placed it
     */
    double getY();

    /**
     * The projectile's height as of the read.
     *
     * @return the height (more negative is higher); 0 until the client has placed it
     */
    double getZ();

    /**
     * The projectile's heading as of the read.
     *
     * @return the angle, 0-2047 (2048 per turn)
     */
    int getOrientation();

    /**
     * The projectile's climb angle as of the read. Kraken extra.
     *
     * @return the angle, 0-2047
     */
    int getPitch();

    /**
     * Whether the client had placed the projectile when it was read: it has a position from its first cycle at or
     * after {@link #getStartCycle()}. Kraken extra.
     *
     * @return true once it has moved
     */
    boolean isStarted();

    /**
     * Whether a Lua plugin hid the projectile ({@code renderProjectiles(false)}). Kraken extra.
     *
     * @return true if the client won't draw it
     */
    @Override
    boolean isHidden();
}
