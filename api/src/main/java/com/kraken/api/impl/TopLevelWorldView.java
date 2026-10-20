package com.kraken.api.impl;

import com.kraken.api.CollisionData;
import com.kraken.api.IndexedObjectSet;
import com.kraken.api.NPC;
import com.kraken.api.Player;
import com.kraken.api.WorldView;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.Collision;
import com.kraken.api.lua.CollisionMap;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.Vector2;
import com.kraken.api.lua.World;

import java.util.List;

/**
 * The top-level {@link WorldView}: the client's top-level {@code World}, read through the Java {@code Scene} (base,
 * size, plane, ground heights, objects), Lua's {@code World} (collision maps) and Lua's {@code Collision} (line
 * tests).
 */
final class TopLevelWorldView implements WorldView {

    private final Scene scene;
    private final World world;
    private final Collision collision;
    private final SceneImpl sceneView;
    private final IndexedObjectSet<NPC> npcs;
    private final IndexedObjectSet<Player> players;

    TopLevelWorldView(ClientImpl client, LuaApi lua) {
        this.scene = lua.scene();
        this.world = lua.world();
        this.collision = lua.collision();
        this.sceneView = new SceneImpl(client, scene);
        this.npcs = new ActorSet<>(client::npcList, client::npc);
        this.players = new ActorSet<>(client::playerList, client::player);
    }

    @Override
    public int getId() {
        return TOPLEVEL;
    }

    @Override
    public boolean isTopLevel() {
        return true;
    }

    @Override
    public com.kraken.api.Scene getScene() {
        return sceneView;
    }

    @Override
    public IndexedObjectSet<NPC> npcs() {
        return npcs;
    }

    @Override
    public IndexedObjectSet<Player> players() {
        return players;
    }

    @Override
    public int getPlane() {
        return scene.getPlane();
    }

    @Override
    public int getBaseX() {
        MapCoord base = scene.getBase();
        return base == null ? -1 : base.x();
    }

    @Override
    public int getBaseY() {
        MapCoord base = scene.getBase();
        return base == null ? -1 : base.z();
    }

    @Override
    public int getSizeX() {
        Vector2 size = scene.getSize();
        return size == null ? 0 : (int) size.x();
    }

    @Override
    public int getSizeY() {
        Vector2 size = scene.getSize();
        return size == null ? 0 : (int) size.y();
    }

    @Override
    public boolean contains(WorldPoint point) {
        MapCoord base = scene.getBase();
        return base != null && inScene(point.getX() - base.x(), point.getY() - base.z());
    }

    @Override
    public boolean contains(LocalPoint point) {
        return point.getWorldView() == TOPLEVEL && point.getX() >= 0 && point.getY() >= 0
                && inScene(point.getSceneX(), point.getSceneY());
    }

    @Override
    public int getTileHeight(int x, int y, int plane) {
        return scene.getGroundHeight(plane, x, y);
    }

    /**
     * Lua's collision maps with their origin taken off: each grid starts a tile outside the scene, and RuneLite's
     * flags are indexed by scene tile.
     *
     * @return one copy per plane, sized to the scene; null if the maps or the scene's size can't be read
     */
    @Override
    public CollisionData[] getCollisionMaps() {
        List<CollisionMap> maps = world.getCollisionMaps();
        Vector2 size = scene.getSize();
        if (maps.isEmpty() || size == null) {
            return null;
        }
        int sizeX = (int) size.x();
        int sizeY = (int) size.y();
        CollisionData[] out = new CollisionData[maps.size()];
        for (int plane = 0; plane < out.length; plane++) {
            CollisionMap map = maps.get(plane);
            int[][] flags = new int[sizeX][sizeY];
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    flags[x][y] = map.getBitFlags(x, y);
                }
            }
            out[plane] = () -> flags;
        }
        return out;
    }

    @Override
    public boolean lineOfSight(WorldPoint from, WorldPoint to) {
        MapCoord base = scene.getBase();
        return base != null && collision.lineOfSight(toScene(from, base), toScene(to, base));
    }

    @Override
    public boolean lineOfWalk(WorldPoint from, WorldPoint to) {
        MapCoord base = scene.getBase();
        return base != null && collision.lineOfWalk(toScene(from, base), toScene(to, base));
    }

    /**
     * Whether a scene tile is inside the scene.
     *
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return true if both are within the scene's size
     */
    private boolean inScene(int sceneX, int sceneY) {
        Vector2 size = scene.getSize();
        return size != null && sceneX >= 0 && sceneY >= 0 && sceneX < size.x() && sceneY < size.y();
    }

    /**
     * A world tile as the scene tile the Lua mirror takes.
     *
     * @param point the world tile
     * @param base  the map base, read once by the caller
     * @return the scene tile on the same plane
     */
    private static MapCoord toScene(WorldPoint point, MapCoord base) {
        return new MapCoord(point.getPlane(), point.getX() - base.x(), point.getY() - base.z());
    }
}
