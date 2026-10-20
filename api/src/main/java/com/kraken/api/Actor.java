package com.kraken.api;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldArea;
import com.kraken.api.coords.WorldPoint;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;

/**
 * An NPC or player, as RuneLite's {@code Actor}.
 *
 * <p>A handle stays on one actor, as RuneLite's objects are the actors themselves: if the client gives the actor's
 * index to another actor, this handle doesn't follow. Getters read the game live while the actor is loaded. Once it is
 * gone, {@link #getName()}, the locations and the NPC id return the last values read, so code handling a despawn can
 * still ask what and where it was; the other getters return their "nothing" values.
 */
public interface Actor extends Renderable {

    /**
     * {@link #getInteractingType()}: interacting with nothing.
     */
    int INTERACTING_NONE = 0;

    /**
     * {@link #getInteractingType()}: interacting with an NPC; {@link #getInteractingIndex()} is its index.
     */
    int INTERACTING_NPC = 1;

    /**
     * {@link #getInteractingType()}: interacting with a player; {@link #getInteractingIndex()} is its index.
     */
    int INTERACTING_PLAYER = 2;

    /**
     * {@link #getInteractingType()}: interacting with a world entity (such as a boat); {@link #getInteractingIndex()}
     * is its id.
     */
    int INTERACTING_WORLD_ENTITY = 3;

    /**
     * The actor's name. An NPC's follows its multi-NPC transform, as the client shows it.
     *
     * @return the name; the last one read once the actor is gone; "" if it was never read
     */
    String getName();

    /**
     * Whether the actor is interacting with something (RuneLite's meaning; Lua's {@code isInteracting} returns the
     * target's index, which is {@link #getInteractingIndex()} here).
     *
     * @return true if it has a target
     */
    boolean isInteracting();

    /**
     * What the actor is interacting with.
     *
     * @return the NPC or player it targets; null for nothing, a world entity, or a target not loaded in the top-level
     *         world
     */
    Actor getInteracting();

    /**
     * The index (or world entity id) of what the actor is interacting with. Kraken extra, mirrors Lua
     * {@code Entity.isInteracting}.
     *
     * @return the target's index, or -1 for nothing
     */
    int getInteractingIndex();

    /**
     * What {@link #getInteractingIndex()} points at. Kraken extra.
     *
     * @return one of the {@code INTERACTING_*} constants
     */
    int getInteractingType();

    /**
     * The actor's world tile: the south-west tile of its footprint.
     *
     * @return the world tile; the last one read once the actor is gone; null if it was never read
     */
    WorldPoint getWorldLocation();

    /**
     * The actor's position in the scene, in local units (the centre of its footprint).
     *
     * @return the local point; the last one read once the actor is gone; null if it was never read
     */
    LocalPoint getLocalLocation();

    /**
     * The tiles the actor stands on.
     *
     * @return its {@link #getWorldLocation()} with {@link #getFootprintSize()} as width and height; null if the
     *         location is unknown
     */
    WorldArea getWorldArea();

    /**
     * How many tiles wide (and long) the actor is.
     *
     * @return 1 for a player; an NPC's type size
     */
    int getFootprintSize();

    /**
     * The world view the actor is in.
     *
     * @return the top-level world view (handles resolve only there, like Lua's)
     */
    WorldView getWorldView();

    /**
     * The outline of the tiles the actor stands on, on the canvas, as {@link Perspective#getCanvasTileAreaPoly} draws
     * it for the actor's {@link #getLocalLocation()} and {@link #getFootprintSize()}.
     *
     * @return the outline in canvas pixels; null if the location is unknown, outside the scene or behind the camera
     */
    Polygon getCanvasTilePoly();

    /**
     * Where to draw text centred over the actor, as {@link Perspective#getCanvasTextLocation} places it.
     *
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param text     the text
     * @param zOffset  how far above the ground, in local units. RuneLite callers pass {@code getLogicalHeight() + 40};
     *                 Kraken doesn't read the actor's height, so pass a fixed lift (about 240 clears a player's head)
     * @return the baseline's left end in canvas pixels; null if the location is unknown or behind the camera
     */
    Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset);

    /**
     * Where to draw an image centred over the actor, as {@link Perspective#getCanvasImageLocation} places it.
     *
     * @param image   the image
     * @param zOffset how far above the ground, in local units
     * @return the image's top-left corner in canvas pixels; null if the location is unknown or behind the camera
     */
    Point getCanvasImageLocation(BufferedImage image, int zOffset);

    /**
     * Whether the actor is dead: it shows a health bar and that bar is empty, as Lua's {@code isDead}.
     *
     * @return true if dead; false once the actor is gone
     */
    boolean isDead();

    /**
     * Whether the actor shows a health bar. Kraken extra, mirrors Lua {@code Entity.isHealthbarRendered}.
     *
     * @return true if it has a health bar showing
     */
    boolean isHealthbarRendered();

    /**
     * Whether one of the actor's head bars shows as empty. Kraken extra, mirrors Lua {@code Entity.isHeadbarEmpty}.
     *
     * @return true if a head bar is empty
     */
    boolean isHeadbarEmpty();

    /**
     * Whether the actor's 2D elements (name, health bar, overhead text) are drawn. Kraken extra, mirrors Lua
     * {@code Entity.isRendered2D}.
     *
     * @return true if they're drawn
     */
    boolean isRendered2D();
}
