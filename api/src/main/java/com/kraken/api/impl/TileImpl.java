package com.kraken.api.impl;

import com.kraken.api.DecorativeObject;
import com.kraken.api.GameObject;
import com.kraken.api.GroundObject;
import com.kraken.api.Point;
import com.kraken.api.Tile;
import com.kraken.api.TileItem;
import com.kraken.api.WallObject;
import com.kraken.api.WorldView;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.GroundObj;
import com.kraken.api.lua.Loc;
import com.kraken.api.lua.MapCoord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * {@link Tile} over the Java scene listing: a scene position plus the map base it was made with. Each object and item
 * getter lists the one tile ({@code Scene.getLocs}/{@code getObjs} over a one-tile area), after checking the map base
 * is still the one the handle was made with.
 */
final class TileImpl implements Tile {

    private final ClientImpl client;
    private final int plane;
    private final int x;
    private final int y;
    private final int baseX;
    private final int baseY;

    TileImpl(ClientImpl client, int plane, int x, int y, int baseX, int baseY) {
        this.client = client;
        this.plane = plane;
        this.x = x;
        this.y = y;
        this.baseX = baseX;
        this.baseY = baseY;
    }

    /**
     * The tile a listed loc or item is on.
     *
     * @param client the client
     * @param tile   the scene tile, as the listing gives it
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     * @return the tile's handle
     */
    static TileImpl at(ClientImpl client, MapCoord tile, int baseX, int baseY) {
        return new TileImpl(client, tile.level(), tile.x(), tile.z(), baseX, baseY);
    }

    @Override
    public WorldPoint getWorldLocation() {
        return new WorldPoint(baseX + x, baseY + y, plane);
    }

    @Override
    public LocalPoint getLocalLocation() {
        return new LocalPoint((x << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE,
                (y << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE, WorldView.TOPLEVEL);
    }

    @Override
    public Point getSceneLocation() {
        return new Point(x, y);
    }

    @Override
    public int getPlane() {
        return plane;
    }

    @Override
    public WallObject getWallObject() {
        return (WallObject) first(Loc.WALL);
    }

    @Override
    public DecorativeObject getDecorativeObject() {
        return (DecorativeObject) first(Loc.WALL_DECOR);
    }

    @Override
    public GroundObject getGroundObject() {
        return (GroundObject) first(Loc.GROUND_DECOR);
    }

    @Override
    public GameObject[] getGameObjects() {
        List<GameObject> objects = new ArrayList<>();
        for (Loc loc : locs()) {
            if (loc.layer() == Loc.GAME_OBJECT) {
                objects.add((GameObject) TileObjectImpl.of(client, loc, baseX, baseY));
            }
        }
        return objects.toArray(new GameObject[0]);
    }

    @Override
    public List<TileItem> getGroundItems() {
        if (!sameScene()) {
            return List.of();
        }
        List<TileItem> items = new ArrayList<>();
        for (GroundObj obj : client.lua().scene().getObjs(plane, x, y, x, y)) {
            items.add(new TileItemImpl(obj.id(), obj.count()));
        }
        return items;
    }

    /**
     * The tile's object in one layer.
     *
     * @param layer the layer, e.g. {@link Loc#WALL}
     * @return the first object listed in that layer, or null
     */
    private TileObjectImpl first(int layer) {
        for (Loc loc : locs()) {
            if (loc.layer() == layer) {
                return TileObjectImpl.of(client, loc, baseX, baseY);
            }
        }
        return null;
    }

    /**
     * The tile's objects, if the scene is still the handle's.
     *
     * @return the objects in listing order; empty once the scene has reloaded elsewhere
     */
    private List<Loc> locs() {
        return sameScene() ? client.lua().scene().getLocs(plane, x, y, x, y) : List.of();
    }

    /**
     * Whether the scene is still the one this handle was made for.
     *
     * @return true if the map base hasn't moved
     */
    private boolean sameScene() {
        MapCoord base = client.lua().scene().getBase();
        return base != null && base.x() == baseX && base.z() == baseY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TileImpl tile && tile.plane == plane && tile.x == x && tile.y == y
                && tile.baseX == baseX && tile.baseY == baseY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(plane, x, y, baseX, baseY);
    }

    @Override
    public String toString() {
        return "Tile[" + getWorldLocation() + "]";
    }
}
