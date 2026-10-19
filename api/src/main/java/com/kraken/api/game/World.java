package com.kraken.api.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the client's Lua {@code osrs.World} namespace: the collision maps of the top-level scene, one per level.
 */
public final class World {

    private final SceneReader scene;

    public World(Client client) {
        this.scene = client.sceneReader();
    }

    /**
     * Mirrors {@code World.getCollisionMap}: a copy of one level's collision flags.
     *
     * @param level the level (plane), 0-3
     * @return the level's collision map, or null for a level the scene doesn't have or if it can't be read (the client
     *         doesn't check the level)
     */
    public CollisionMap getCollisionMap(int level) {
        return scene.collisionMap(scene.sceneOf(scene.topWorld()), level);
    }

    /**
     * Mirrors {@code World.getCollisionMaps}: a copy of every level's collision flags.
     *
     * @return the collision maps in level order (index = level), empty if they can't be read
     */
    public List<CollisionMap> getCollisionMaps() {
        long sc = scene.sceneOf(scene.topWorld());
        int count = scene.collisionMapCount(sc);
        List<CollisionMap> maps = new ArrayList<>(count);
        for (int level = 0; level < count; level++) {
            CollisionMap map = scene.collisionMap(sc, level);
            if (map == null) {
                return List.of();
            }
            maps.add(map);
        }
        return maps;
    }
}
