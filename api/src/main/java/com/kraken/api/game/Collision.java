package com.kraken.api.game;

/**
 * Mirrors the client's Lua {@code osrs.Collision} namespace: collision checks on tiles of the top-level scene, read
 * live from the client (only the tiles a check looks at are read). For many checks against the same flags, take a
 * {@link CollisionMap} from {@link World#getCollisionMap} instead.
 *
 * <p>Coordinates are scene tiles, as {@link Client#coord()} returns them.
 */
public final class Collision {

    private final SceneReader scene;

    public Collision(Client client) {
        this.scene = client.sceneReader();
    }

    /**
     * Mirrors {@code Collision.getBitFlags}: a tile's collision flags ({@link CollisionFlag}).
     *
     * <p>Differs from the client on purpose, as {@link CollisionMap#getBitFlags} does: the native function indexes the
     * level's grid without subtracting its origin, so in Lua it reads the tile one south-west of the one asked for.
     *
     * @param coordinate the tile
     * @return the tile's flags, or {@link CollisionFlag#UNLOADED} outside the grid, for a level the scene doesn't have,
     *         or if it can't be read
     */
    public int getBitFlags(MapCoord coordinate) {
        CollisionLines.Grid grid = grid(coordinate.level());
        if (grid == null) {
            return CollisionFlag.UNLOADED;
        }
        return grid.at(coordinate.x() - grid.originX(), coordinate.z() - grid.originZ());
    }

    /**
     * Mirrors {@code Collision.lineOfSight}: whether the end tile can be seen from the start tile, using the start
     * tile's level.
     *
     * @param startCoord the start tile
     * @param endCoord   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different levels, either is
     *         outside the grid, or the flags can't be read
     */
    public boolean lineOfSight(MapCoord startCoord, MapCoord endCoord) {
        if (startCoord.level() != endCoord.level()) {
            return false;
        }
        CollisionLines.Grid grid = grid(startCoord.level());
        return grid != null && CollisionLines.sight(grid, startCoord.x(), startCoord.z(), endCoord.x(), endCoord.z());
    }

    /**
     * Mirrors {@code Collision.lineOfWalk}: whether one can walk in a straight line from the start tile to the end
     * tile, using the start tile's level.
     *
     * @param startCoord the start tile
     * @param endCoord   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different levels, the end tile
     *         is outside the grid, or the flags can't be read
     */
    public boolean lineOfWalk(MapCoord startCoord, MapCoord endCoord) {
        if (startCoord.level() != endCoord.level()) {
            return false;
        }
        CollisionLines.Grid grid = grid(startCoord.level());
        return grid != null && CollisionLines.walk(grid, startCoord.x(), startCoord.z(), endCoord.x(), endCoord.z());
    }

    private CollisionLines.Grid grid(int level) {
        return scene.liveCollision(scene.sceneOf(scene.topWorld()), level);
    }
}
