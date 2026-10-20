package com.kraken.api;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;

import java.awt.Graphics2D;
import java.awt.Polygon;

/**
 * An object (scenery) on a tile, as RuneLite's {@code TileObject}: a {@link WallObject}, {@link DecorativeObject},
 * {@link GameObject} or {@link GroundObject}. Its name and actions come from {@link Client#getObjectDefinition} by
 * {@link #getId()}.
 *
 * <p>A value: it holds the object's id, slot and tile as one read of the scene found them, with the map base of that
 * scene. Two reads of the same object in the same scene give equal objects, so a {@code Set<TileObject>} filled from
 * spawn events can be emptied by the despawn events. The canvas helpers read the camera each call.
 */
public interface TileObject {

    /**
     * The bit {@link #getHash()} keeps the plane at.
     */
    int HASH_PLANE_SHIFT = 14;

    /**
     * A key for the object, in RuneLite's bit layout: scene x in bits 0-6, scene y in 7-13, plane in 14-15, the tag
     * type (2, an object) in 16-18, the id in 20-51 and the world view (12 bits) in 52-63. Built from the object's
     * fields rather than read: RuneLite's bit 19 (set for some walls) isn't read, so it is 0.
     *
     * @return the hash
     */
    long getHash();

    /**
     * The object's x in local units: its tile's centre, or for a game object the centre of its footprint.
     *
     * @return the local x
     */
    int getX();

    /**
     * The object's y in local units.
     *
     * @return the local y
     */
    int getY();

    /**
     * The height of the ground at the object's local location.
     *
     * @return the height (more negative is higher); 0 if it can't be read
     */
    int getZ();

    /**
     * The plane of the tile the object is on.
     *
     * @return the plane, 0-3
     */
    int getPlane();

    /**
     * The world view the object is in.
     *
     * @return the top-level world view
     */
    WorldView getWorldView();

    /**
     * The object's id.
     *
     * @return the object (loc type) id, as in {@code gameval.ObjectID}
     */
    int getId();

    /**
     * The object's world location. For an object bigger than one tile, the tile nearest its centre, rounded to the
     * south-west, as RuneLite's.
     *
     * @return the world tile
     */
    WorldPoint getWorldLocation();

    /**
     * The object's centre in local units.
     *
     * @return {@link #getX()}, {@link #getY()} in the object's world view
     */
    LocalPoint getLocalLocation();

    /**
     * Where the object's centre appears on the canvas, on the ground.
     *
     * @return the canvas position, or null if it is outside the scene or behind the camera
     */
    Point getCanvasLocation();

    /**
     * Where a point above the object's centre appears on the canvas.
     *
     * @param zOffset how far above the ground, in local units
     * @return the canvas position, or null if it is outside the scene or behind the camera
     */
    Point getCanvasLocation(int zOffset);

    /**
     * The outline of the tile the object is on, or for a game object the tiles it covers.
     *
     * @return the outline in canvas pixels, or null if it is outside the scene or a corner is behind the camera
     */
    Polygon getCanvasTilePoly();

    /**
     * Where to draw text centred over the object.
     *
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param text     the text
     * @param zOffset  how far above the ground, in local units
     * @return the baseline's left end in canvas pixels, or null if the object is outside the scene or behind the
     *         camera
     */
    Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset);
}
