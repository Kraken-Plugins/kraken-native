package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.coords.LocalPoint;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Objects;

/**
 * A spot animation the server plays on a tile: a spell's splash, a cannon's smoke, a special attack's flash… Get them
 * from {@link WorldView#getGraphicsObjects()} or {@link Client#getGraphicsObjects()}.
 *
 * <p>The server sends each with its spot anim, its tile, a height above the ground and a delay. The client plays it
 * from {@link #getStartCycle()} on, one frame of the spot anim's animation at a time, and drops it once the animation
 * ends or the world's plane changes.
 *
 * <p>A snapshot of one read of the world's list: {@link #getAnimationFrame()} is as of that read, so read the list
 * again for a fresh one. {@link #finished()} reads the game when called. Snapshots of the same object, from any read,
 * are equal. Cycles are client cycles (20 ms), {@link Client#getGameCycle()}'s clock.
 *
 * <p>Not provided: {@code setFinished} and {@code getAnimation}.
 */
@Getter
public final class GraphicsObject {

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * Where the object is in the client's memory. Internal: the event source and {@link #finished()} recognise the
     * object by it.
     */
    private final long address;

    /**
     * The object's id: its spot anim, as in {@code gameval.SpotanimID}.
     */
    private final int id;

    /**
     * The centre of the tile the object plays on, in the top-level scene.
     */
    private final LocalPoint location;

    /**
     * The plane the object plays on. The client drops it once the world's plane is another.
     */
    private final int level;

    /**
     * The height the object is drawn at: the ground's height at its tile less the height the server sent (more
     * negative is higher), in local height units.
     */
    private final int z;

    /**
     * The cycle the object starts playing on: the cycle the server's packet arrived plus its delay. Until then the
     * client neither animates nor draws it.
     */
    private final int startCycle;

    /**
     * The frame of the spot anim's animation the object was showing when read; -1 before it has started.
     */
    private final int animationFrame;

    /**
     * Whether the animation had ended when the object was read (the client drops it on the next frame). Internal:
     * {@link #finished()} falls back on it when the list can't be read.
     */
    private final boolean animationEnded;

    /**
     * A snapshot of one graphics object. Internal: {@code GraphicsObjectReader} makes them.
     *
     * @param client         the client {@link #finished()} reads through
     * @param address        where the object is in the client's memory
     * @param id             the spot anim id
     * @param location       the centre of its tile, in the top-level scene
     * @param level          its plane
     * @param z              the height it is drawn at
     * @param startCycle     the cycle it starts playing on
     * @param animationFrame the animation frame it was showing
     * @param animationEnded whether its animation had ended
     */
    public GraphicsObject(Client client, long address, int id, LocalPoint location, int level, int z, int startCycle,
                          int animationFrame, boolean animationEnded) {
        this.client = client;
        this.address = address;
        this.id = id;
        this.location = location;
        this.level = level;
        this.z = z;
        this.startCycle = startCycle;
        this.animationFrame = animationFrame;
        this.animationEnded = animationEnded;
    }

    /**
     * The world view the object plays in.
     *
     * @return the top-level world view, the only one read
     */
    public WorldView getWorldView() {
        return client.getTopLevelWorldView();
    }

    /**
     * Whether the object is done animating, as of now: the client has dropped it from the world's list (its animation
     * ended, the world's plane changed, or the player logged out), or its animation has ended and the client drops it
     * on the next frame. Walks the world's list, so it reads the game.
     *
     * @return true once the object is done; as of the snapshot's read if the list can't be read right now
     */
    public boolean finished() {
        return client.getGraphicsObjectReader().finished(this);
    }

    /**
     * Whether another snapshot is of the same object: the same place in the client's memory with the same id, tile,
     * level, height and start cycle (the frame may differ).
     *
     * @param o the other object
     * @return true for snapshots of the same graphics object
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof GraphicsObject other && address == other.address && id == other.id
                && level == other.level && z == other.z && startCycle == other.startCycle
                && location.equals(other.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, id, location, level, z, startCycle);
    }

    @Override
    public String toString() {
        return "GraphicsObject[" + id + " at " + location + " level " + level + "]";
    }
}
