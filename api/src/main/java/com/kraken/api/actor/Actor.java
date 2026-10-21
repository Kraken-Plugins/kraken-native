package com.kraken.api.actor;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldArea;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.reader.ActorReader;
import com.kraken.api.internal.FineCoord;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.scene.WorldView;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;

/**
 * An NPC or player.
 *
 * <p>A handle stays on one actor: it is pinned to the actor's address in the top-level world when the client hands it
 * out, and if the client gives the actor's index to another actor, this handle doesn't follow. Getters read the game
 * live while the actor is loaded. Once it is gone, {@link #getName()}, the locations and the NPC id return the last
 * values read, so code handling a despawn can still ask what and where it was; the other getters return their "nothing"
 * values.
 */
public abstract class Actor {

    /**
     * {@link #getInteractingType()}: interacting with nothing.
     */
    public static final int INTERACTING_NONE = 0;

    /**
     * {@link #getInteractingType()}: interacting with an NPC; {@link #getInteractingIndex()} is its index.
     */
    public static final int INTERACTING_NPC = 1;

    /**
     * {@link #getInteractingType()}: interacting with a player; {@link #getInteractingIndex()} is its index.
     */
    public static final int INTERACTING_PLAYER = 2;

    /**
     * {@link #getInteractingType()}: interacting with a world entity (such as a boat); {@link #getInteractingIndex()}
     * is its id.
     */
    public static final int INTERACTING_WORLD_ENTITY = 3;

    final Client client;
    final ActorReader actors;
    final int index;
    final long pinnedAddress;
    private volatile String name = "";
    private volatile WorldPoint worldLocation;
    private volatile LocalPoint localLocation;

    /**
     * A handle pinned to the actor at an address.
     *
     * @param client        the client
     * @param index         the actor's index
     * @param pinnedAddress the actor's address when the handle was made
     */
    Actor(Client client, int index, long pinnedAddress) {
        this.client = client;
        this.actors = client.getActorReader();
        this.index = index;
        this.pinnedAddress = pinnedAddress;
    }

    /**
     * The actor at this handle's index, if it's still the one the handle was made for.
     *
     * @return its address; 0 if nothing is loaded at the index, or another actor has taken it
     */
    abstract long address();

    /**
     * The actor's name as the client shows it, read from its struct.
     *
     * @param address the actor's address
     * @return the name, or "" if it can't be read
     */
    abstract String readName(long address);

    /**
     * The actor's name. An NPC's follows its multi-NPC transform, as the client shows it.
     *
     * @return the name; the last one read once the actor is gone; "" if it was never read
     */
    public String getName() {
        long address = address();
        if (address == 0) {
            return name;
        }
        String live = readName(address);
        name = live;
        return live;
    }

    /**
     * Whether the actor is interacting with something. Lua's {@code isInteracting} returns the target's index, which is
     * {@link #getInteractingIndex()} here.
     *
     * @return true if it has a target
     */
    public boolean isInteracting() {
        return actors.interacting(address()) != -1;
    }

    /**
     * What the actor is interacting with.
     *
     * @return the NPC or player it targets; null for nothing, a world entity, or a target not loaded in the top-level
     *         world
     */
    public Actor getInteracting() {
        long address = address();
        int target = actors.interacting(address);
        return target == -1 ? null : client.actor(actors.interactingType(address), target);
    }

    /**
     * The index (or world entity id) of what the actor is interacting with. Mirrors Lua {@code Entity.isInteracting}.
     *
     * @return the target's index, or -1 for nothing
     */
    public int getInteractingIndex() {
        return actors.interacting(address());
    }

    /**
     * What {@link #getInteractingIndex()} points at.
     *
     * @return one of the {@code INTERACTING_*} constants
     */
    public int getInteractingType() {
        return actors.interactingType(address());
    }

    /**
     * The actor's world tile: the south-west tile of its footprint.
     *
     * @return the world tile; the last one read once the actor is gone; null if it was never read
     */
    public WorldPoint getWorldLocation() {
        MapCoord tile = actors.coord(address());
        WorldPoint world = tile == null ? null : client.toWorld(tile);
        if (world == null) {
            return worldLocation;
        }
        worldLocation = world;
        return world;
    }

    /**
     * The actor's position in the scene, in local units (the centre of its footprint).
     *
     * @return the local point; the last one read once the actor is gone; null if it was never read
     */
    public LocalPoint getLocalLocation() {
        FineCoord fine = actors.fineCoord(address());
        if (fine.isMissing()) {
            return localLocation;
        }
        LocalPoint local = new LocalPoint(fine.x(), fine.z(), WorldView.TOPLEVEL);
        localLocation = local;
        return local;
    }

    /**
     * The tiles the actor stands on.
     *
     * @return its {@link #getWorldLocation()} with {@link #getFootprintSize()} as width and height; null if the
     *         location is unknown
     */
    public WorldArea getWorldArea() {
        WorldPoint at = getWorldLocation();
        if (at == null) {
            return null;
        }
        int size = getFootprintSize();
        return new WorldArea(at, size, size);
    }

    /**
     * How many tiles wide (and long) the actor is.
     *
     * @return 1 for a player; an NPC's type size
     */
    public abstract int getFootprintSize();

    /**
     * The world view the actor is in.
     *
     * @return the top-level world view (handles resolve only there)
     */
    public WorldView getWorldView() {
        return client.getTopLevelWorldView();
    }

    /**
     * The outline of the tiles the actor stands on, on the canvas, as {@link Perspective#getCanvasTileAreaPoly} draws
     * it for the actor's {@link #getLocalLocation()} and {@link #getFootprintSize()}.
     *
     * @return the outline in canvas pixels; null if the location is unknown, outside the scene or behind the camera
     */
    public Polygon getCanvasTilePoly() {
        LocalPoint local = getLocalLocation();
        return local == null ? null : Perspective.getCanvasTileAreaPoly(client, local, getFootprintSize());
    }

    /**
     * Where to draw text centred over the actor, as {@link Perspective#getCanvasTextLocation} places it.
     *
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param text     the text
     * @param zOffset  how far above the ground, in local units. Callers usually pass {@code getLogicalHeight() + 40};
     *                 the API doesn't read the actor's height, so pass a fixed lift (about 240 clears a player's head)
     * @return the baseline's left end in canvas pixels; null if the location is unknown or behind the camera
     */
    public Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset) {
        LocalPoint local = getLocalLocation();
        return local == null ? null : Perspective.getCanvasTextLocation(client, graphics, local, text, zOffset);
    }

    /**
     * Where to draw an image centred over the actor, as {@link Perspective#getCanvasImageLocation} places it.
     *
     * @param image   the image
     * @param zOffset how far above the ground, in local units
     * @return the image's top-left corner in canvas pixels; null if the location is unknown or behind the camera
     */
    public Point getCanvasImageLocation(BufferedImage image, int zOffset) {
        LocalPoint local = getLocalLocation();
        return local == null ? null : Perspective.getCanvasImageLocation(client, local, image, zOffset);
    }

    /**
     * Whether the actor is dead: it shows a health bar and that bar is empty, as Lua's {@code isDead}.
     *
     * @return true if dead; false once the actor is gone
     */
    public boolean isDead() {
        return actors.dead(address());
    }

    /**
     * Whether the actor shows a health bar. Mirrors Lua {@code Entity.isHealthbarRendered}.
     *
     * @return true if it has a health bar showing
     */
    public boolean isHealthbarRendered() {
        return actors.healthbarRendered(address());
    }

    /**
     * Whether one of the actor's head bars shows as empty. Mirrors Lua {@code Entity.isHeadbarEmpty}.
     *
     * @return true if a head bar is empty
     */
    public boolean isHeadbarEmpty() {
        return actors.headbarEmpty(address());
    }

    /**
     * Whether the actor's 2D elements (name, health bar, overhead text) are drawn. Mirrors Lua
     * {@code Entity.isRendered2D}, reading the flag on the actor rather than a Lua object's own copy.
     *
     * @return true if they're drawn, or if the flag can't be read
     */
    public boolean isRendered2D() {
        return actors.rendered2D(address());
    }

    /**
     * Whether a Lua plugin has hidden the actor's model (Lua's {@code render3D(false)}): the opposite of Lua's
     * {@code Entity.isRendered3D}, reading the flag on the actor, so it also sees what other plugins hid.
     *
     * @return true if the client won't draw its model; false once the actor is gone
     */
    public boolean isHidden() {
        return !actors.rendered3D(address());
    }

    /**
     * How many steps the actor has queued on its path. Mirrors Lua {@code ClientOp.playerRouteLength} (which the client
     * binds for players only): as the Lua docs warn, it isn't the length of the whole route.
     *
     * @return the queued steps; 0 when standing still or once the actor is gone
     */
    public int getPathLength() {
        return actors.routeLength(address());
    }

    /**
     * Reads the values a handle keeps once its actor is gone (name and location), so they are fresh when it goes. The
     * event source calls this when an actor spawns and once per game tick while it tracks the actor.
     * Internal: used by the API's own packages; plugins don't need it.
     */
    public void remember() {
        getName();
        getWorldLocation();
        getLocalLocation();
    }
}
