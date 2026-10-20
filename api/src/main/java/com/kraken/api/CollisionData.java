package com.kraken.api;

/**
 * One plane's collision flags, as RuneLite's {@code CollisionData}. Get them from {@link WorldView#getCollisionMaps()}.
 *
 * <p>Unlike RuneLite's, which reads the client's live array, this is a copy taken when the maps were fetched (as Lua's
 * {@code CollisionMap} is), so it doesn't follow doors opening or the map reloading. Fetch the maps again for fresh
 * flags.
 */
public interface CollisionData {

    /**
     * The flags of every tile in the scene.
     *
     * @return the flags indexed {@code [sceneX][sceneY]}, covering the scene's size; test them with the
     *         {@link CollisionDataFlag} bits
     */
    int[][] getFlags();
}
