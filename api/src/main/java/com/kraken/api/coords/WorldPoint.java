package com.kraken.api.coords;

import com.kraken.api.Client;
import com.kraken.api.WorldView;
import lombok.Value;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;

/**
 * A world tile, as RuneLite's {@code WorldPoint}: the coordinates the wiki uses. Lua's {@code MapCoord} is a scene
 * tile instead (relative to the loaded map's base); a world tile is that plus the base ({@link #fromScene}).
 *
 * <p>Inside an instance this is the instance's own tile, as RuneLite's {@link #fromLocal} gives it. RuneLite's
 * {@code fromLocalInstance} maps it back to the template's real-world tile using the instance chunk table, which
 * Kraken doesn't read, so it isn't offered.
 */
@Value
public class WorldPoint {

    private static final int REGION_BITS = 6;
    private static final int REGION_MASK = (1 << REGION_BITS) - 1;

    /**
     * The tile's x: east.
     */
    int x;

    /**
     * The tile's y: north (Lua's {@code z}).
     */
    int y;

    /**
     * The tile's plane, 0-3 (Lua's {@code level}).
     */
    int plane;

    /**
     * The world tile under a local point, on its world view's current plane.
     *
     * @param client the client
     * @param local  the local point
     * @return the world tile, or null if the local point's world view isn't loaded
     */
    public static WorldPoint fromLocal(Client client, LocalPoint local) {
        WorldView view = client.getWorldView(local.getWorldView());
        return view == null ? null : fromLocal(view, local.getX(), local.getY(), view.getPlane());
    }

    /**
     * The world tile under a local position of a world view.
     *
     * @param view  the world view the position is in
     * @param x     the local x
     * @param y     the local y
     * @param plane the plane to put in the result
     * @return the world tile
     */
    public static WorldPoint fromLocal(WorldView view, int x, int y, int plane) {
        return new WorldPoint((x >>> LOCAL_COORD_BITS) + view.getBaseX(), (y >>> LOCAL_COORD_BITS) + view.getBaseY(),
                plane);
    }

    /**
     * The world tile of a scene tile: the scene tile plus the scene's base.
     *
     * @param view   the world view whose scene the tile is in
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @param plane  the plane
     * @return the world tile
     */
    public static WorldPoint fromScene(WorldView view, int sceneX, int sceneY, int plane) {
        return new WorldPoint(sceneX + view.getBaseX(), sceneY + view.getBaseY(), plane);
    }

    /**
     * A tile given by its map square (region) and its place in it.
     *
     * @param regionId the region id ({@code regionX << 8 | regionY})
     * @param regionX  the tile's x in the region, 0-63
     * @param regionY  the tile's y in the region, 0-63
     * @param plane    the plane
     * @return the world tile
     */
    public static WorldPoint fromRegion(int regionId, int regionX, int regionY, int plane) {
        return new WorldPoint(((regionId >>> 8) << REGION_BITS) + regionX, ((regionId & 0xff) << REGION_BITS) + regionY,
                plane);
    }

    /**
     * The Chebyshev (king-move) distance to another tile.
     *
     * @param other the other tile
     * @return the larger of the x and y distances; {@link Integer#MAX_VALUE} on another plane
     */
    public int distanceTo(WorldPoint other) {
        return other.plane != plane ? Integer.MAX_VALUE : distanceTo2D(other);
    }

    /**
     * The Chebyshev distance to the nearest tile of an area.
     *
     * @param area the area
     * @return the distance; {@link Integer#MAX_VALUE} on another plane
     */
    public int distanceTo(WorldArea area) {
        return area.getPlane() != plane ? Integer.MAX_VALUE : toWorldArea().distanceTo(area);
    }

    /**
     * The Chebyshev distance to another tile, ignoring planes.
     *
     * @param other the other tile
     * @return the larger of the x and y distances
     */
    public int distanceTo2D(WorldPoint other) {
        return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
    }

    /**
     * This tile moved along x.
     *
     * @param dx tiles to move east (negative for west)
     * @return the moved tile
     */
    public WorldPoint dx(int dx) {
        return new WorldPoint(x + dx, y, plane);
    }

    /**
     * This tile moved along y.
     *
     * @param dy tiles to move north (negative for south)
     * @return the moved tile
     */
    public WorldPoint dy(int dy) {
        return new WorldPoint(x, y + dy, plane);
    }

    /**
     * This tile on another plane.
     *
     * @param dz planes to move up (negative for down)
     * @return the moved tile
     */
    public WorldPoint dz(int dz) {
        return new WorldPoint(x, y, plane + dz);
    }

    /**
     * The map square (64×64 region) this tile is in.
     *
     * @return the region id, {@code regionX << 8 | regionY}
     */
    public int getRegionID() {
        return (x >> REGION_BITS) << 8 | (y >> REGION_BITS);
    }

    /**
     * This tile's x within its region.
     *
     * @return 0-63
     */
    public int getRegionX() {
        return x & REGION_MASK;
    }

    /**
     * This tile's y within its region.
     *
     * @return 0-63
     */
    public int getRegionY() {
        return y & REGION_MASK;
    }

    /**
     * Whether this tile is in any of some areas, plane included.
     *
     * @param areas the areas
     * @return true if one of them contains it
     */
    public boolean isInArea(WorldArea... areas) {
        for (WorldArea area : areas) {
            if (area.contains(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this tile is in any of some areas, ignoring planes.
     *
     * @param areas the areas
     * @return true if one of them contains it in x and y
     */
    public boolean isInArea2D(WorldArea... areas) {
        for (WorldArea area : areas) {
            if (area.contains2D(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * This tile as a one-tile area.
     *
     * @return a 1×1 area on this tile
     */
    public WorldArea toWorldArea() {
        return new WorldArea(this, 1, 1);
    }
}
