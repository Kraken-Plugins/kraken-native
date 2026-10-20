package com.kraken.api.lua;

import java.util.List;

/**
 * The top-level scene: what is on its tiles, where it sits in the world, and the ground's shape. Not a Lua namespace;
 * the Lua API only finds one loc or item at a time ({@link ClientOp#locFind}, {@link ClientOp#objFind}), and this lists
 * them, for the dev tools and for code that wants to look around.
 *
 * <p>Coordinates are scene tiles, as {@link ClientOp#coord()} returns them: the tile relative to the loaded map's base
 * ({@link #getBase()}). Levels are the scene's own; a tile under a bridge is on the level above the one
 * {@link ClientOp#locCoord()} reports for it.
 */
public final class Scene {

    private final SceneReader scene;
    private final ActorReader actors;

    public Scene(ClientOp client) {
        this.scene = client.sceneReader();
        this.actors = client.actorReader();
    }

    /**
     * The loaded map's base: the world tile at scene tile (0, 0). It moves each time the map is rebuilt (when you
     * walk far enough, or teleport).
     *
     * @return the base as a world coordinate on level 0, or null if it can't be read
     */
    public MapCoord getBase() {
        int[] base = scene.mapBase();
        return base == null ? null : new MapCoord(0, base[0], base[1]);
    }

    /**
     * Turns a scene tile into a world tile (the coordinates the wiki and RuneLite use).
     *
     * @param coord a scene tile
     * @return the world tile on the same level, or null if the map base can't be read
     */
    public MapCoord toWorld(MapCoord coord) {
        int[] base = scene.mapBase();
        return base == null ? null : new MapCoord(coord.level(), coord.x() + base[0], coord.z() + base[1]);
    }

    /**
     * Turns a world tile into a scene tile.
     *
     * @param coord a world tile
     * @return the scene tile on the same level (possibly outside the scene), or null if the map base can't be read
     */
    public MapCoord toScene(MapCoord coord) {
        int[] base = scene.mapBase();
        return base == null ? null : new MapCoord(coord.level(), coord.x() - base[0], coord.z() - base[1]);
    }

    /**
     * The level the top-level world is on: the plane {@link ClientOp#npcCoord} and {@link ClientOp#playerCoord}
     * report, and the one the local player stands on.
     *
     * @return the plane, 0-3; -1 if it can't be read
     */
    public int getPlane() {
        return actors.plane(scene.topWorld());
    }

    /**
     * The scene's size in tiles (104 by 104 for a normal map).
     *
     * @return {@code x} = width, {@code y} = length; null if it can't be read
     */
    public Vector2 getSize() {
        int[] size = scene.worldSize(scene.topWorld());
        return size == null ? null : new Vector2(size[0], size[1]);
    }

    /**
     * Every loc within {@code radius} tiles of a tile, on its level.
     *
     * @param center the tile in the middle
     * @param radius how far out to look, in tiles (0 for the one tile)
     * @return the locs, tile by tile; a game object spanning several tiles is listed once, on its origin tile
     */
    public List<Loc> getLocs(MapCoord center, int radius) {
        return getLocs(center.level(), center.x() - radius, center.z() - radius, center.x() + radius,
                center.z() + radius);
    }

    /**
     * Every loc on the tiles of a rectangle, on one level.
     *
     * @param level the level
     * @param x1    the lowest tile x
     * @param z1    the lowest tile z
     * @param x2    the highest tile x (inclusive)
     * @param z2    the highest tile z (inclusive)
     * @return the locs, tile by tile
     */
    public List<Loc> getLocs(int level, int x1, int z1, int x2, int z2) {
        return scene.locs(scene.sceneOf(scene.topWorld()), level, x1, z1, x2, z2);
    }

    /**
     * Every loc in the scene, on every level. Reads the whole scene (tens of thousands of tiles), so it takes a while;
     * prefer {@link #getLocs(MapCoord, int)} for an area.
     *
     * @return the locs, level by level and tile by tile
     */
    public List<Loc> getAllLocs() {
        return scene.allLocs(scene.sceneOf(scene.topWorld()));
    }

    /**
     * Every item on the ground within {@code radius} tiles of a tile, on its level.
     *
     * @param center the tile in the middle
     * @param radius how far out to look, in tiles (0 for the one tile)
     * @return the items, tile by tile in pile order (the order {@link ClientOp#objFind} searches)
     */
    public List<GroundObj> getObjs(MapCoord center, int radius) {
        return getObjs(center.level(), center.x() - radius, center.z() - radius, center.x() + radius,
                center.z() + radius);
    }

    /**
     * Every item on the ground on the tiles of a rectangle, on one level.
     *
     * @param level the level
     * @param x1    the lowest tile x
     * @param z1    the lowest tile z
     * @param x2    the highest tile x (inclusive)
     * @param z2    the highest tile z (inclusive)
     * @return the items, tile by tile in pile order
     */
    public List<GroundObj> getObjs(int level, int x1, int z1, int x2, int z2) {
        return scene.objs(scene.topWorld(), level, x1, z1, x2, z2);
    }

    /**
     * Every item on the ground in the scene, on every level. Reads the pile grid a column at a time and walks only the
     * piles that hold something: a few hundred reads plus a few per item, so far cheaper than
     * {@link #getObjs(int, int, int, int, int)} over the whole scene, but still more than an area.
     *
     * @return the items, level by level, tile by tile, then in pile order
     */
    public List<GroundObj> getAllObjs() {
        return scene.allObjs(scene.topWorld());
    }

    /**
     * The ground's height at a fine position: bilinear between the tile's corner heights, from the level above under
     * a bridge, as the client works out an actor's height but without a ground decoration's offset.
     *
     * @param level the level
     * @param fineX the fine x (128 per tile)
     * @param fineZ the fine z
     * @return the height (more negative is higher); 0 off the scene or if it can't be read
     */
    public int getGroundHeight(int level, int fineX, int fineZ) {
        long world = scene.topWorld();
        return world == 0 ? 0 : actors.groundHeight(world, fineX, fineZ, level);
    }

    /**
     * A point on the ground, ready for {@link Graphics#worldToScreenCoord}: the fine position with the ground's height.
     *
     * @param level the level
     * @param fineX the fine x (128 per tile)
     * @param fineZ the fine z
     * @return the fine coordinate with the ground height in {@code y}
     */
    public FineCoord groundPoint(int level, int fineX, int fineZ) {
        return new FineCoord(fineX, getGroundHeight(level, fineX, fineZ), fineZ);
    }

    /**
     * The middle of a tile on the ground.
     *
     * @param tile a scene tile
     * @return the tile's centre with the ground height in {@code y}
     */
    public FineCoord getTileCenter(MapCoord tile) {
        return groundPoint(tile.level(), tile.x() * FineCoord.TILE + FineCoord.TILE / 2,
                tile.z() * FineCoord.TILE + FineCoord.TILE / 2);
    }

    /**
     * The four corners of a tile on the ground, for outlining it.
     *
     * @param tile a scene tile
     * @return the south-west, south-east, north-east and north-west corners, with the ground height in {@code y}
     */
    public FineCoord[] getTileCorners(MapCoord tile) {
        int x = tile.x() * FineCoord.TILE;
        int z = tile.z() * FineCoord.TILE;
        int level = tile.level();
        int t = FineCoord.TILE;
        return new FineCoord[]{groundPoint(level, x, z), groundPoint(level, x + t, z),
                groundPoint(level, x + t, z + t), groundPoint(level, x, z + t)};
    }
}
