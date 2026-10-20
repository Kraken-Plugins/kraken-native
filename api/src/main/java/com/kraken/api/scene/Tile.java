package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.GroundObj;
import com.kraken.api.internal.Loc;
import com.kraken.api.internal.MapCoord;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * One tile of a scene, as RuneLite's {@code Tile}: what stands on it, and where it is.
 *
 * <p>A handle on a scene position, made for the scene as it was when the handle was made (its map base). The object
 * and item getters read the game each call, listing the one tile, after checking the map base is still the one the
 * handle was made with. Once the scene has reloaded with another base, they read as empty, since the position now
 * belongs to another part of the world; get the tile again from {@link Scene}.
 *
 * <p>Two handles on the same position of the same scene are equal.
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class Tile {

    private final Client client;

    /**
     * The plane (scene level) the tile is on. Under a bridge, the client draws the tile one level lower
     * ({@link #getRenderLevel()}); this is the tile's own level, as RuneLite's.
     */
    @Getter
    @EqualsAndHashCode.Include
    private final int plane;

    @EqualsAndHashCode.Include
    private final int x;

    @EqualsAndHashCode.Include
    private final int y;

    @EqualsAndHashCode.Include
    private final int baseX;

    @EqualsAndHashCode.Include
    private final int baseY;

    /**
     * A handle on a scene position.
     *
     * @param client the client
     * @param plane  the plane
     * @param x      the scene x
     * @param y      the scene y
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     */
    Tile(Client client, int plane, int x, int y, int baseX, int baseY) {
        this.client = client;
        this.plane = plane;
        this.x = x;
        this.y = y;
        this.baseX = baseX;
        this.baseY = baseY;
    }

    /**
     * The tile a listed object or item is on.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param client the client
     * @param tile   the scene tile, as the listing gives it
     * @param baseX  the map base x the scene was read with
     * @param baseY  the map base y
     * @return the tile's handle
     */
    public static Tile at(Client client, MapCoord tile, int baseX, int baseY) {
        return new Tile(client, tile.level(), tile.x(), tile.z(), baseX, baseY);
    }

    /**
     * The tile's world location.
     *
     * @return the world tile: the scene tile plus the map base the handle was made with
     */
    public WorldPoint getWorldLocation() {
        return new WorldPoint(baseX + x, baseY + y, plane);
    }

    /**
     * The tile's centre in local units.
     *
     * @return the centre, {@code tile * 128 + 64} on each axis
     */
    public LocalPoint getLocalLocation() {
        return new LocalPoint((x << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE,
                (y << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE, WorldView.TOPLEVEL);
    }

    /**
     * The tile's position in the scene.
     *
     * @return the scene x and y
     */
    public Point getSceneLocation() {
        return new Point(x, y);
    }

    /**
     * The level the client draws the tile on: one lower than {@link #getPlane()} under a bridge (the level-1 tile's
     * bridge setting is set), as RuneLite's. Lua's {@code ClientOp.locCoord} reports this level. Read now.
     *
     * @return the render level, 0-3; {@link #getPlane()} if the scene has reloaded or the settings can't be read
     */
    public int getRenderLevel() {
        if (!sameScene()) {
            return plane;
        }
        return client.getSceneReader().visibleLevel(client.getSceneReader().sceneOf(client.getSceneReader().topWorld()), plane, x, y);
    }

    /**
     * The wall on the tile.
     *
     * @return the wall, or null if there is none
     */
    public WallObject getWallObject() {
        return (WallObject) first(Loc.WALL);
    }

    /**
     * The decoration on the tile's wall.
     *
     * @return the decoration, or null if there is none
     */
    public DecorativeObject getDecorativeObject() {
        return (DecorativeObject) first(Loc.WALL_DECOR);
    }

    /**
     * The decoration on the tile's floor (flowers, a rug…). Not an item lying on the ground: those are
     * {@link #getGroundItems()}.
     *
     * @return the ground decoration, or null if there is none
     */
    public GroundObject getGroundObject() {
        return (GroundObject) first(Loc.GROUND_DECOR);
    }

    /**
     * The game objects whose south-west tile this is. Differs from RuneLite's in two ways: an object bigger than a
     * tile is only listed on its south-west tile (RuneLite lists it on every tile it covers), and the array holds just
     * the objects, without RuneLite's empty (null) slots.
     *
     * @return the game objects, empty if there are none
     */
    public GameObject[] getGameObjects() {
        List<GameObject> objects = new ArrayList<>();
        for (Loc loc : locs()) {
            if (loc.layer() == Loc.GAME_OBJECT) {
                objects.add((GameObject) TileObject.of(client, loc, baseX, baseY));
            }
        }
        return objects.toArray(new GameObject[0]);
    }

    /**
     * The items lying on the tile, as new handles each call (they aren't the handles the item spawn events carry).
     *
     * @return the items in pile order; empty rather than RuneLite's null when there are none
     */
    public List<TileItem> getGroundItems() {
        if (!sameScene()) {
            return List.of();
        }
        List<TileItem> items = new ArrayList<>();
        for (GroundObj obj : client.getSceneReader().objs(client.getSceneReader().topWorld(), plane, x, y, x, y)) {
            items.add(new TileItem(obj.id(), obj.count(), this));
        }
        return items;
    }

    /**
     * The tile's object in one layer.
     *
     * @param layer the layer, e.g. {@link Loc#WALL}
     * @return the first object listed in that layer, or null
     */
    private TileObject first(int layer) {
        for (Loc loc : locs()) {
            if (loc.layer() == layer) {
                return TileObject.of(client, loc, baseX, baseY);
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
        if (!sameScene()) {
            return List.of();
        }
        return client.getSceneReader().locs(client.getSceneReader().sceneOf(client.getSceneReader().topWorld()), plane, x, y, x, y);
    }

    /**
     * Whether the scene is still the one this handle was made for.
     *
     * @return true if the map base hasn't moved
     */
    private boolean sameScene() {
        int[] base = client.getSceneReader().mapBase();
        return base != null && base[0] == baseX && base[1] == baseY;
    }

    @Override
    public String toString() {
        return "Tile[" + getWorldLocation() + "]";
    }
}
