package com.kraken.api;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;

/**
 * One loaded world, as RuneLite's {@code WorldView}: the client's {@code World} struct, with its actors, its plane and
 * where its scene sits in the world. The top-level world is the main map; sub-worlds (such as boats) aren't wrapped
 * yet, so {@link Client#getTopLevelWorldView()} is the only view.
 */
public interface WorldView {

    /**
     * The top-level world view's id.
     */
    int TOPLEVEL = -1;

    /**
     * The view's id.
     *
     * @return {@link #TOPLEVEL} for the top-level world
     */
    int getId();

    /**
     * Whether this is the top-level world.
     *
     * @return true for the main map
     */
    boolean isTopLevel();

    /**
     * This world's scene: its tiles and what stands on them.
     *
     * @return the scene, the same object from call to call
     */
    Scene getScene();

    /**
     * The NPCs loaded in this world.
     *
     * @return the NPCs by index
     */
    IndexedObjectSet<NPC> npcs();

    /**
     * The players loaded in this world.
     *
     * @return the players by index
     */
    IndexedObjectSet<Player> players();

    /**
     * The level this world is on, the one its actors' tiles are reported on.
     *
     * @return the plane, 0-3; -1 if it can't be read
     */
    int getPlane();

    /**
     * The world x of the scene's south-west tile: what turns a scene x into a world x. It moves each time the map is
     * rebuilt.
     *
     * @return the base x; -1 if it can't be read
     */
    int getBaseX();

    /**
     * The world y of the scene's south-west tile.
     *
     * @return the base y; -1 if it can't be read
     */
    int getBaseY();

    /**
     * The scene's size along x.
     *
     * @return the width in tiles (104 for a normal map); 0 if it can't be read
     */
    int getSizeX();

    /**
     * The scene's size along y.
     *
     * @return the length in tiles; 0 if it can't be read
     */
    int getSizeY();

    /**
     * Whether a world tile is in this view's scene. Only x and y are checked: a tile on another plane is still in the
     * scene.
     *
     * @param point the world tile
     * @return true if it is inside the loaded scene
     */
    boolean contains(WorldPoint point);

    /**
     * Whether a local point is in this view's scene.
     *
     * @param point the local point
     * @return true if it belongs to this view and is inside the loaded scene
     */
    boolean contains(LocalPoint point);

    /**
     * The ground's height at a point of this view's scene: bilinear between the tile's corner heights, taken from the
     * level above under a bridge, as RuneLite's {@code Perspective.getTileHeight} works it out from the scene's tile
     * heights. Kraken extra, mirrors the Java {@code Scene.getGroundHeight}; {@link Perspective#getTileHeight} calls
     * it.
     *
     * @param x     the local x
     * @param y     the local y
     * @param plane the plane
     * @return the height (more negative is higher); 0 outside the scene or if it can't be read
     */
    int getTileHeight(int x, int y, int plane);

    /**
     * Every plane's collision flags, as RuneLite's. Mirrors Lua {@code World.getCollisionMaps}: a copy taken now, so
     * it doesn't follow later changes (see {@link CollisionData}).
     *
     * @return the flags by plane (index = plane), each covering the scene; null if they can't be read
     */
    CollisionData[] getCollisionMaps();

    /**
     * Whether the end tile can be seen from the start tile, by the client's own line-of-sight test. Kraken extra,
     * mirrors Lua {@code Collision.lineOfSight}.
     *
     * @param from the start tile
     * @param to   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different planes or outside
     *         the scene, or the flags can't be read
     */
    boolean lineOfSight(WorldPoint from, WorldPoint to);

    /**
     * Whether one can walk in a straight line from the start tile to the end tile, by the client's own test. Kraken
     * extra, mirrors Lua {@code Collision.lineOfWalk}.
     *
     * @param from the start tile
     * @param to   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different planes or the end
     *         is outside the scene, or the flags can't be read
     */
    boolean lineOfWalk(WorldPoint from, WorldPoint to);
}
