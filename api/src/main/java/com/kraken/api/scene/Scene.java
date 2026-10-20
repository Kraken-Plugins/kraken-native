package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.internal.GroundObj;
import com.kraken.api.internal.Loc;
import com.kraken.api.internal.SceneReader;

import java.util.ArrayList;
import java.util.List;

/**
 * A world view's scene: its tiles, and where it sits in the world. Get the top-level one from
 * {@link Client#getScene()} or {@link WorldView#getScene()}.
 *
 * <p>Tiles are lazy handles: making them is cheap, and each getter on a tile reads the game when called. Reading what
 * is on every tile of a scene (about 43,000 of them) is not cheap, so plugins that follow objects or ground items
 * should keep track of them with the spawn events ({@code GameObjectSpawned}, {@code ItemSpawned} and friends) rather
 * than walk {@link #getTiles()}. To learn the scene's objects or items now, read them in one pass with
 * {@link #getTileObjects()} and {@link #getGroundItems()}, or an area with their overloads.
 */
public final class Scene {

    private static final int PLANES = 4;

    private final Client client;
    private final SceneReader reader;

    /**
     * The top-level scene.
     *
     * @param client the client
     */
    Scene(Client client) {
        this.client = client;
        this.reader = client.getSceneReader();
    }

    /**
     * Every tile of the scene, as handles on the scene as it is now. After the scene reloads (a new map base), a
     * handle reads as empty; get the tiles again.
     *
     * @return the tiles by {@code [plane][sceneX][sceneY]}, four planes of {@link #getSizeX()} by {@link #getSizeY()};
     *         empty planes if the scene can't be read
     */
    public Tile[][][] getTiles() {
        int[] base = reader.mapBase();
        int[] size = size();
        int sizeX = base == null || size == null ? 0 : size[0];
        int sizeY = base == null || size == null ? 0 : size[1];
        Tile[][][] tiles = new Tile[PLANES][sizeX][sizeY];
        for (int plane = 0; plane < PLANES; plane++) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    tiles[plane][x][y] = new Tile(client, plane, x, y, base[0], base[1]);
                }
            }
        }
        return tiles;
    }

    /**
     * One tile of the scene. It saves building the whole {@link #getTiles()} array for one tile.
     *
     * @param plane  the plane, 0-3
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return the tile's handle, or null outside the scene or if the scene can't be read
     */
    public Tile getTile(int plane, int sceneX, int sceneY) {
        int[] base = reader.mapBase();
        int[] size = size();
        if (base == null || size == null || plane < 0 || plane >= PLANES || sceneX < 0 || sceneY < 0
                || sceneX >= size[0] || sceneY >= size[1]) {
            return null;
        }
        return new Tile(client, plane, sceneX, sceneY, base[0], base[1]);
    }

    /**
     * Every object in the scene, on every plane, in one pass. It is the read the object spawn events make once per
     * tick (tens of thousands of reads, a few hundred milliseconds), for code that needs the scene's objects now, such
     * as a plugin started mid-session. Walking {@link #getTiles()} for the same costs several reads per tile and
     * getter.
     *
     * @return the walls, wall decorations, game objects and floor decorations, plane by plane and tile by tile; empty
     *         if the scene can't be read
     */
    public List<TileObject> getTileObjects() {
        int[] base = reader.mapBase();
        return base == null ? List.of() : objects(reader.allLocs(reader.sceneOf(reader.topWorld())), base);
    }

    /**
     * Every object on the tiles of a rectangle of the scene, on one plane, in one pass. It is far cheaper than the
     * whole scene for an area around the player.
     *
     * @param plane the plane
     * @param minX  the lowest scene x
     * @param minY  the lowest scene y
     * @param maxX  the highest scene x (inclusive)
     * @param maxY  the highest scene y (inclusive)
     * @return the objects, tile by tile; a game object spanning several tiles is listed once, on its south-west tile
     */
    public List<TileObject> getTileObjects(int plane, int minX, int minY, int maxX, int maxY) {
        int[] base = reader.mapBase();
        return base == null ? List.of()
                : objects(reader.locs(reader.sceneOf(reader.topWorld()), plane, minX, minY, maxX, maxY), base);
    }

    /**
     * Every item lying on the ground in the scene, on every plane. It reads the pile grid a column at a time and walks
     * only the piles that hold something, a few hundred reads plus a few per item.
     *
     * @return new handles on the items, plane by plane, tile by tile, then in pile order; empty if the scene can't be
     *         read
     */
    public List<TileItem> getGroundItems() {
        int[] base = reader.mapBase();
        return base == null ? List.of() : items(reader.allObjs(reader.topWorld()), base);
    }

    /**
     * Every item lying on the tiles of a rectangle of the scene, on one plane.
     *
     * @param plane the plane
     * @param minX  the lowest scene x
     * @param minY  the lowest scene y
     * @param maxX  the highest scene x (inclusive)
     * @param maxY  the highest scene y (inclusive)
     * @return new handles on the items, tile by tile in pile order
     */
    public List<TileItem> getGroundItems(int plane, int minX, int minY, int maxX, int maxY) {
        int[] base = reader.mapBase();
        return base == null ? List.of() : items(reader.objs(reader.topWorld(), plane, minX, minY, maxX, maxY), base);
    }

    /**
     * The world x of the scene's south-west tile, as {@link WorldView#getBaseX()}.
     *
     * @return the base x; -1 if it can't be read
     */
    public int getBaseX() {
        int[] base = reader.mapBase();
        return base == null ? -1 : base[0];
    }

    /**
     * The world y of the scene's south-west tile, as {@link WorldView#getBaseY()}.
     *
     * @return the base y; -1 if it can't be read
     */
    public int getBaseY() {
        int[] base = reader.mapBase();
        return base == null ? -1 : base[1];
    }

    /**
     * The scene's size along x.
     *
     * @return the width in tiles; 0 if it can't be read
     */
    public int getSizeX() {
        int[] size = size();
        return size == null ? 0 : size[0];
    }

    /**
     * The scene's size along y.
     *
     * @return the length in tiles; 0 if it can't be read
     */
    public int getSizeY() {
        int[] size = size();
        return size == null ? 0 : size[1];
    }

    /**
     * The id of the world view the scene belongs to.
     *
     * @return {@link WorldView#TOPLEVEL} for the main map
     */
    public int getWorldViewId() {
        return WorldView.TOPLEVEL;
    }

    private int[] size() {
        return reader.worldSize(reader.topWorld());
    }

    private List<TileObject> objects(List<Loc> locs, int[] base) {
        List<TileObject> objects = new ArrayList<>(locs.size());
        for (Loc loc : locs) {
            objects.add(TileObject.of(client, loc, base[0], base[1]));
        }
        return objects;
    }

    private List<TileItem> items(List<GroundObj> objs, int[] base) {
        List<TileItem> items = new ArrayList<>(objs.size());
        for (GroundObj obj : objs) {
            items.add(new TileItem(obj.id(), obj.count(), Tile.at(client, obj.coord(), base[0], base[1])));
        }
        return items;
    }
}
