package com.kraken.api;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;

import java.util.List;

/**
 * One tile of a scene, as RuneLite's {@code Tile}: what stands on it, and where it is.
 *
 * <p>A handle on a scene position, made for the scene as it was when the handle was made (its map base). The object
 * and item getters read the game each call. Once the scene has reloaded with another base, they read as empty, since
 * the position now belongs to another part of the world; get the tile again from {@link Scene}.
 *
 * <p>Two handles on the same position of the same scene are equal.
 */
public interface Tile {

    /**
     * The tile's world location.
     *
     * @return the world tile: the scene tile plus the map base the handle was made with
     */
    WorldPoint getWorldLocation();

    /**
     * The tile's centre in local units.
     *
     * @return the centre, {@code tile * 128 + 64} on each axis
     */
    LocalPoint getLocalLocation();

    /**
     * The tile's position in the scene.
     *
     * @return the scene x and y
     */
    Point getSceneLocation();

    /**
     * The plane (scene level) the tile is on. Under a bridge, the client draws the tile one level lower; this is the
     * tile's own level, as RuneLite's.
     *
     * @return the plane, 0-3
     */
    int getPlane();

    /**
     * The wall on the tile.
     *
     * @return the wall, or null if there is none
     */
    WallObject getWallObject();

    /**
     * The decoration on the tile's wall.
     *
     * @return the decoration, or null if there is none
     */
    DecorativeObject getDecorativeObject();

    /**
     * The game objects whose south-west tile this is. Differs from RuneLite's in two ways: an object bigger than a
     * tile is only listed on its south-west tile (RuneLite lists it on every tile it covers), and the array holds just
     * the objects, without RuneLite's empty (null) slots.
     *
     * @return the game objects, empty if there are none
     */
    GameObject[] getGameObjects();

    /**
     * The decoration on the tile's floor (flowers, a rug…). Not an item lying on the ground: those are
     * {@link #getGroundItems()}.
     *
     * @return the ground decoration, or null if there is none
     */
    GroundObject getGroundObject();

    /**
     * The items lying on the tile, as new handles each call (they aren't the handles the item spawn events carry).
     *
     * @return the items in pile order; empty rather than RuneLite's null when there are none
     */
    List<TileItem> getGroundItems();
}
