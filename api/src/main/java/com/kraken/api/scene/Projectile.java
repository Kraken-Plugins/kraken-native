package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.actor.Actor;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.ProjectileEndpoint;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * A projectile in flight (an arrow, a spell, a cannonball…). Get them from {@link Client#getProjectiles()}.
 *
 * <p>The server sends each projectile with its two ends, their heights, its timing and its spot anim. The client keeps
 * it until the cycle passes {@link #getEndCycle()}. From {@link #getStartCycle()} on it moves it every cycle towards
 * where the target is now, so a projectile aimed at an actor follows that actor. It has no position until it has moved
 * once ({@link #isStarted()}).
 *
 * <p>The server sends some projectiles with a key ({@link #getKey()}), and can then retarget one in flight: the
 * client carries on from where it is towards the new target. Those follow a curve rather than a slope, so their
 * {@link #getSlope()} and {@link #getStartPos()} are 0, and a Lua plugin can't hide them.
 *
 * <p>A snapshot of one read of the client's projectile list: its position and angles are as of that read, so read the
 * list again for fresh ones. {@link #getRemainingCycles()} and the actor getters read the game when called. Cycles are
 * client cycles (20 ms), {@link Client#getGameCycle()}'s clock.
 *
 * <p>Not provided: the getters {@code getInteracting}, {@code getTarget}, {@code getX1}, {@code getY1},
 * {@code getFloor} and {@code getHeight}, the animation getters, and {@code setEndCycle}.
 */
@Getter
public final class Projectile {

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * Where the projectile is in the client's memory. Internal: the event source tells keyed projectiles apart by it.
     */
    private final long address;

    /**
     * The key the server sent the projectile with, which lets it retarget the projectile in flight; -1 for a projectile
     * sent without one.
     */
    private final int key;

    /**
     * The projectile's id: its spot anim, as in {@code gameval.SpotanimID}.
     */
    private final int id;

    /**
     * Where the projectile comes from, as the client stores it. Internal: the event source keys projectiles by it.
     */
    private final ProjectileEndpoint source;

    /**
     * Where the projectile is going, as the client stores it. Internal: the event source keys projectiles by it.
     */
    private final ProjectileEndpoint target;

    /**
     * The cycle the projectile sets off on; until then the client neither moves nor draws it.
     */
    private final int startCycle;

    /**
     * The cycle the projectile lands on; the client drops it once the cycle passes this.
     */
    private final int endCycle;

    /**
     * How steeply the projectile climbs at the start, in 1/256 of a turn: larger slopes arc more. 0 for a keyed
     * projectile.
     */
    private final int slope;

    /**
     * How far along its path from the source the projectile appears, in local units. 0 for a keyed projectile.
     */
    private final int startPos;

    /**
     * Whether the client had placed the projectile when it was read: it has a position from its first cycle at or
     * after {@link #getStartCycle()}.
     */
    private final boolean started;

    /**
     * The projectile's x as of the read, in local units; 0 until the client has placed it ({@link #isStarted()}).
     */
    private final double x;

    /**
     * The projectile's y (north) as of the read, in local units; 0 until the client has placed it.
     */
    private final double y;

    /**
     * The projectile's height as of the read (more negative is higher); 0 until the client has placed it.
     */
    private final double z;

    /**
     * The projectile's heading as of the read: 0-2047 (2048 per turn), the angle the client draws the model at.
     */
    private final int orientation;

    /**
     * The projectile's climb angle as of the read, 0-2047.
     */
    private final int pitch;

    /**
     * Whether a Lua plugin hid the projectile ({@code renderProjectiles(false)}), so the client won't draw it. Always
     * false for a keyed projectile, which {@code renderProjectiles} doesn't reach.
     */
    private final boolean hidden;

    /**
     * The client cycle when the projectile was read.
     */
    private final int readCycle;

    /**
     * A snapshot of one projectile. Internal: {@code ProjectileReader} makes them.
     *
     * @param client      the client the actors are looked up in
     * @param address     where it is in the client's memory
     * @param key         the key it was sent with, or -1
     * @param id          the spot anim id
     * @param source      where it comes from
     * @param target      where it is going
     * @param startCycle  the cycle it sets off on
     * @param endCycle    the cycle it lands on
     * @param slope       its initial climb
     * @param startPos    how far along the path it appears
     * @param started     whether the client has placed it yet
     * @param x           the local x
     * @param y           the local y (north)
     * @param z           the height
     * @param orientation the heading
     * @param pitch       the climb angle
     * @param hidden      whether a Lua plugin hid it
     * @param readCycle   the client cycle of the read
     */
    public Projectile(Client client, long address, int key, int id, ProjectileEndpoint source, ProjectileEndpoint target,
                      int startCycle, int endCycle, int slope, int startPos, boolean started, double x, double y, double z,
                      int orientation, int pitch, boolean hidden, int readCycle) {
        this.client = client;
        this.address = address;
        this.key = key;
        this.id = id;
        this.source = source;
        this.target = target;
        this.startCycle = startCycle;
        this.endCycle = endCycle;
        this.slope = slope;
        this.startPos = startPos;
        this.started = started;
        this.x = x;
        this.y = y;
        this.z = z;
        this.orientation = orientation;
        this.pitch = pitch;
        this.hidden = hidden;
        this.readCycle = readCycle;
    }

    /**
     * The level the projectile starts on.
     *
     * @return the source tile's plane
     */
    public int getSourceLevel() {
        return source.tile().level();
    }

    /**
     * The tile the projectile starts at, as the server sent it.
     *
     * @return the source world tile
     */
    public WorldPoint getSourcePoint() {
        return source.point();
    }

    /**
     * The actor the projectile starts at, looked up now.
     *
     * @return the NPC or player, or null for a tile, or if the actor isn't loaded in the top-level world
     */
    public Actor getSourceActor() {
        return client.actor(source.actorType(), source.actorIndex());
    }

    /**
     * The level the projectile ends on.
     *
     * @return the target tile's plane
     */
    public int getTargetLevel() {
        return target.tile().level();
    }

    /**
     * The tile the projectile is aimed at, as the server sent it. For a projectile aimed at an actor, the client
     * follows the actor instead.
     *
     * @return the target world tile
     */
    public WorldPoint getTargetPoint() {
        return target.point();
    }

    /**
     * The actor the projectile is aimed at, looked up now.
     *
     * @return the NPC or player, or null for a tile (an area attack, say), or if the actor isn't loaded
     */
    public Actor getTargetActor() {
        return client.actor(target.actorType(), target.actorIndex());
    }

    /**
     * How high above the source the projectile starts.
     *
     * @return the height offset in local height units (larger is higher)
     */
    public int getStartHeight() {
        return source.height();
    }

    /**
     * How high above the target the projectile ends.
     *
     * @return the height offset in local height units (larger is higher)
     */
    public int getEndHeight() {
        return target.height();
    }

    /**
     * How long until the projectile lands, as of now (one read of the cycle counter).
     *
     * @return {@link #getEndCycle()} less the current cycle, in client cycles; less the cycle of the read if the
     *         counter can't be read
     */
    public int getRemainingCycles() {
        int cycle = client.getGameCycle();
        return endCycle - (cycle < 0 ? readCycle : cycle);
    }

    @Override
    public String toString() {
        return "Projectile[" + id + " " + getSourcePoint() + " -> " + getTargetPoint() + "]";
    }
}
