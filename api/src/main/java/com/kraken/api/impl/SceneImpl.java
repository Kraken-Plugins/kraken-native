package com.kraken.api.impl;

import com.kraken.api.Scene;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import com.kraken.api.WorldView;
import com.kraken.api.lua.Loc;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Vector2;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-level {@link Scene}, over the Java {@code Scene} of the Lua mirror. Tiles are {@link TileImpl} handles made
 * with the map base as it is when they are asked for.
 */
final class SceneImpl implements Scene {

    private static final int PLANES = 4;

    private final ClientImpl client;
    private final com.kraken.api.lua.Scene scene;

    SceneImpl(ClientImpl client, com.kraken.api.lua.Scene scene) {
        this.client = client;
        this.scene = scene;
    }

    @Override
    public Tile[][][] getTiles() {
        MapCoord base = scene.getBase();
        Vector2 size = scene.getSize();
        int sizeX = base == null || size == null ? 0 : (int) size.x();
        int sizeY = base == null || size == null ? 0 : (int) size.y();
        Tile[][][] tiles = new Tile[PLANES][sizeX][sizeY];
        for (int plane = 0; plane < PLANES; plane++) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    tiles[plane][x][y] = new TileImpl(client, plane, x, y, base.x(), base.z());
                }
            }
        }
        return tiles;
    }

    @Override
    public Tile getTile(int plane, int sceneX, int sceneY) {
        MapCoord base = scene.getBase();
        Vector2 size = scene.getSize();
        if (base == null || size == null || plane < 0 || plane >= PLANES || sceneX < 0 || sceneY < 0
                || sceneX >= size.x() || sceneY >= size.y()) {
            return null;
        }
        return new TileImpl(client, plane, sceneX, sceneY, base.x(), base.z());
    }

    @Override
    public List<TileObject> getTileObjects() {
        MapCoord base = scene.getBase();
        if (base == null) {
            return List.of();
        }
        List<Loc> locs = scene.getAllLocs();
        List<TileObject> objects = new ArrayList<>(locs.size());
        for (Loc loc : locs) {
            objects.add(TileObjectImpl.of(client, loc, base.x(), base.z()));
        }
        return objects;
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
    public int getWorldViewId() {
        return WorldView.TOPLEVEL;
    }
}
