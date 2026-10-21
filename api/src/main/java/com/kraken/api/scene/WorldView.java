package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.actor.IndexedObjectSet;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.collision.CollisionData;
import com.kraken.api.collision.CollisionDataFlag;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.CollisionLines;
import com.kraken.api.internal.reader.SceneReader;
import lombok.Getter;

/**
 * One loaded world: the client's {@code World} struct, with its actors, its plane and where its scene sits in the
 * world. The top-level world is the main map; sub-worlds (such as boats) aren't wrapped yet, so
 * {@link Client#getTopLevelWorldView()} is the only view.
 */
public final class WorldView {

    /**
     * The top-level world view's id.
     */
    public static final int TOPLEVEL = -1;

    private final Client client;
    private final SceneReader reader;

    /**
     * This world's scene: its tiles and what stands on them. The same object from call to call.
     */
    @Getter
    private final Scene scene;

    private final IndexedObjectSet<NPC> npcs;
    private final IndexedObjectSet<Player> players;

    /**
     * The top-level world view.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client the client
     */
    public WorldView(Client client) {
        this.client = client;
        this.reader = client.getSceneReader();
        this.scene = new Scene(client);
        this.npcs = new IndexedObjectSet<>(client::npcList, client::npc);
        this.players = new IndexedObjectSet<>(client::playerList, client::player);
    }

    /**
     * The view's id.
     *
     * @return {@link #TOPLEVEL} for the top-level world
     */
    public int getId() {
        return TOPLEVEL;
    }

    /**
     * Whether this is the top-level world.
     *
     * @return true for the main map
     */
    public boolean isTopLevel() {
        return true;
    }

    /**
     * The NPCs loaded in this world.
     *
     * @return the NPCs by index
     */
    public IndexedObjectSet<NPC> npcs() {
        return npcs;
    }

    /**
     * The players loaded in this world.
     *
     * @return the players by index
     */
    public IndexedObjectSet<Player> players() {
        return players;
    }

    /**
     * The level this world is on, the one its actors' tiles are reported on.
     *
     * @return the plane, 0-3; -1 if it can't be read
     */
    public int getPlane() {
        return client.getActorReader().plane(reader.topWorld());
    }

    /**
     * The world x of the scene's south-west tile: what turns a scene x into a world x. It moves each time the map is
     * rebuilt.
     *
     * @return the base x; -1 if it can't be read
     */
    public int getBaseX() {
        return scene.getBaseX();
    }

    /**
     * The world y of the scene's south-west tile.
     *
     * @return the base y; -1 if it can't be read
     */
    public int getBaseY() {
        return scene.getBaseY();
    }

    /**
     * The scene's size along x.
     *
     * @return the width in tiles (104 for a normal map); 0 if it can't be read
     */
    public int getSizeX() {
        return scene.getSizeX();
    }

    /**
     * The scene's size along y.
     *
     * @return the length in tiles; 0 if it can't be read
     */
    public int getSizeY() {
        return scene.getSizeY();
    }

    /**
     * Whether a world tile is in this view's scene. Only x and y are checked: a tile on another plane is still in the
     * scene.
     *
     * @param point the world tile
     * @return true if it is inside the loaded scene
     */
    public boolean contains(WorldPoint point) {
        int[] base = reader.mapBase();
        return base != null && inScene(point.getX() - base[0], point.getY() - base[1]);
    }

    /**
     * Whether a local point is in this view's scene.
     *
     * @param point the local point
     * @return true if it belongs to this view and is inside the loaded scene
     */
    public boolean contains(LocalPoint point) {
        return point.getWorldView() == TOPLEVEL && point.getX() >= 0 && point.getY() >= 0
                && inScene(point.getSceneX(), point.getSceneY());
    }

    /**
     * The ground's height at a point of this view's scene: bilinear between the tile's corner heights, taken from the
     * level above under a bridge. {@link Perspective#getTileHeight} calls it.
     *
     * @param x     the local x
     * @param y     the local y
     * @param plane the plane
     * @return the height (more negative is higher); 0 outside the scene or if it can't be read
     */
    public int getTileHeight(int x, int y, int plane) {
        long world = reader.topWorld();
        return world == 0 ? 0 : client.getActorReader().groundHeight(world, x, y, plane);
    }

    /**
     * Every plane's collision flags. Lua's {@code World.getCollisionMaps}: a copy taken now, so it doesn't follow
     * later changes (see {@link CollisionData}).
     *
     * @return the flags by plane (index = plane), each covering the scene; null if they can't be read
     */
    public CollisionData[] getCollisionMaps() {
        long world = reader.topWorld();
        long sc = reader.sceneOf(world);
        int[] size = reader.worldSize(world);
        int count = reader.collisionMapCount(sc);
        if (size == null || count == 0) {
            return null;
        }
        CollisionData[] out = new CollisionData[count];
        for (int plane = 0; plane < count; plane++) {
            out[plane] = reader.collisionMap(sc, plane, size[0], size[1]);
            if (out[plane] == null) {
                return null;
            }
        }
        return out;
    }

    /**
     * Whether the end tile can be seen from the start tile, by the client's own line-of-sight test, read live (only
     * the tiles the line crosses). Mirrors Lua {@code Collision.lineOfSight}.
     *
     * @param from the start tile
     * @param to   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different planes or outside
     *         the scene, or the flags can't be read
     */
    public boolean lineOfSight(WorldPoint from, WorldPoint to) {
        int[] base = reader.mapBase();
        CollisionLines.Grid grid = base == null || from.getPlane() != to.getPlane() ? null : liveGrid(from.getPlane());
        return grid != null && CollisionLines.sight(grid, from.getX() - base[0], from.getY() - base[1],
                to.getX() - base[0], to.getY() - base[1]);
    }

    /**
     * Whether one can walk in a straight line from the start tile to the end tile, by the client's own test, read
     * live. Mirrors Lua {@code Collision.lineOfWalk}.
     *
     * @param from the start tile
     * @param to   the end tile
     * @return true if nothing blocks the line; false if something does, the tiles are on different planes or the end
     *         is outside the scene, or the flags can't be read
     */
    public boolean lineOfWalk(WorldPoint from, WorldPoint to) {
        int[] base = reader.mapBase();
        CollisionLines.Grid grid = base == null || from.getPlane() != to.getPlane() ? null : liveGrid(from.getPlane());
        return grid != null && CollisionLines.walk(grid, from.getX() - base[0], from.getY() - base[1],
                to.getX() - base[0], to.getY() - base[1]);
    }

    /**
     * One tile's collision flags, read live from the client. Mirrors Lua {@code Collision.getBitFlags}, without its
     * off-by-one: the native function reads the tile one south-west of the one asked for.
     *
     * @param point the world tile
     * @return the tile's flags ({@link CollisionDataFlag}), or {@link CollisionDataFlag#UNLOADED} outside the grid, for
     *         a plane the scene doesn't have, or if it can't be read
     */
    public int getCollisionFlags(WorldPoint point) {
        int[] base = reader.mapBase();
        CollisionLines.Grid grid = base == null ? null : liveGrid(point.getPlane());
        if (grid == null) {
            return CollisionDataFlag.UNLOADED;
        }
        return grid.at(point.getX() - base[0] - grid.originX(), point.getY() - base[1] - grid.originZ());
    }

    /**
     * One plane's collision grid, read a tile at a time.
     *
     * @param plane the plane
     * @return the grid, or null if the plane has none or it can't be read
     */
    private CollisionLines.Grid liveGrid(int plane) {
        return reader.liveCollision(reader.sceneOf(reader.topWorld()), plane);
    }

    /**
     * Whether a scene tile is inside the scene.
     *
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return true if both are within the scene's size
     */
    private boolean inScene(int sceneX, int sceneY) {
        int[] size = reader.worldSize(reader.topWorld());
        return size != null && sceneX >= 0 && sceneY >= 0 && sceneX < size[0] && sceneY < size[1];
    }
}
