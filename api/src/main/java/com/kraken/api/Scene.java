package com.kraken.api;

import java.util.List;

/**
 * A world view's scene, as RuneLite's {@code Scene}: its tiles, and where it sits in the world. Get the top-level one
 * from {@link Client#getScene()} or {@link WorldView#getScene()}.
 *
 * <p>Tiles are lazy handles: making them is cheap, and each getter on a tile reads the game when called. Reading what
 * is on every tile of a scene (about 43,000 of them) is not cheap, so plugins that follow objects or ground items
 * should keep track of them with the spawn events ({@code GameObjectSpawned}, {@code ItemSpawned} and friends), as
 * RuneLite plugins do, rather than walk {@link #getTiles()}. To learn the scene's objects now, read them in one pass
 * with {@link #getTileObjects()}.
 */
public interface Scene {

    /**
     * Every tile of the scene, as handles on the scene as it is now. After the scene reloads (a new map base), a
     * handle reads as empty; get the tiles again.
     *
     * @return the tiles by {@code [plane][sceneX][sceneY]}, four planes of {@link #getSizeX()} by {@link #getSizeY()};
     *         empty planes if the scene can't be read
     */
    Tile[][][] getTiles();

    /**
     * One tile of the scene. Kraken extra: it saves building the whole {@link #getTiles()} array for one tile.
     *
     * @param plane  the plane, 0-3
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return the tile's handle, or null outside the scene or if the scene can't be read
     */
    Tile getTile(int plane, int sceneX, int sceneY);

    /**
     * Every object in the scene, on every plane, in one pass. Kraken extra: it is the read the object spawn events
     * make once per tick (tens of thousands of reads, a few hundred milliseconds), for code that needs the scene's
     * objects now, such as a plugin started mid-session. Walking {@link #getTiles()} for the same costs several reads
     * per tile and getter.
     *
     * @return the walls, wall decorations, game objects and floor decorations, plane by plane and tile by tile; empty
     *         if the scene can't be read
     */
    List<TileObject> getTileObjects();

    /**
     * The world x of the scene's south-west tile, as {@link WorldView#getBaseX()}.
     *
     * @return the base x; -1 if it can't be read
     */
    int getBaseX();

    /**
     * The world y of the scene's south-west tile, as {@link WorldView#getBaseY()}.
     *
     * @return the base y; -1 if it can't be read
     */
    int getBaseY();

    /**
     * The scene's size along x. Kraken extra; RuneLite's scenes are always {@link Perspective#SCENE_SIZE} wide.
     *
     * @return the width in tiles; 0 if it can't be read
     */
    int getSizeX();

    /**
     * The scene's size along y. Kraken extra.
     *
     * @return the length in tiles; 0 if it can't be read
     */
    int getSizeY();

    /**
     * The id of the world view the scene belongs to.
     *
     * @return {@link WorldView#TOPLEVEL} for the main map
     */
    int getWorldViewId();
}
