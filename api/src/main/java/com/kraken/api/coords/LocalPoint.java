package com.kraken.api.coords;

import com.kraken.api.Client;
import com.kraken.api.scene.WorldView;
import lombok.Value;

import static com.kraken.api.Perspective.LOCAL_COORD_BITS;
import static com.kraken.api.Perspective.LOCAL_HALF_TILE_SIZE;

/**
 * A position in a world view's scene in local units (128 per tile, from the scene's south-west corner), as RuneLite's
 * {@code LocalPoint}. Lua's {@code FineCoord} is the same space with the height added: {@code LocalPoint(fine.x,
 * fine.z)}.
 */
@Value
public class LocalPoint {

    /**
     * East, in local units.
     */
    int x;

    /**
     * North, in local units (Lua's fine {@code z}).
     */
    int y;

    /**
     * The id of the world view whose scene this is in; {@link WorldView#TOPLEVEL} for the main map.
     */
    int worldView;

    /**
     * The centre of a world tile, in the top-level scene.
     *
     * @param client the client
     * @param world  the world tile
     * @return the tile's centre; null if the tile is on another plane than the scene or outside it
     */
    public static LocalPoint fromWorld(Client client, WorldPoint world) {
        return fromWorld(client.getTopLevelWorldView(), world);
    }

    /**
     * The centre of a world tile, in a world view's scene.
     *
     * @param view  the world view
     * @param world the world tile
     * @return the tile's centre; null if the tile is on another plane than the view or outside its scene
     */
    public static LocalPoint fromWorld(WorldView view, WorldPoint world) {
        if (view.getPlane() != world.getPlane()) {
            return null;
        }
        return fromWorld(view, world.getX(), world.getY());
    }

    /**
     * The centre of a world tile, in a world view's scene, whatever its plane.
     *
     * @param view the world view
     * @param x    the world x
     * @param y    the world y
     * @return the tile's centre, or null if the tile is outside the scene
     */
    public static LocalPoint fromWorld(WorldView view, int x, int y) {
        if (!view.contains(new WorldPoint(x, y, 0))) {
            return null;
        }
        return fromScene(x - view.getBaseX(), y - view.getBaseY(), view);
    }

    /**
     * The centre of a scene tile.
     *
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @param view   the world view whose scene it is
     * @return the tile's centre: {@code tile * 128 + 64} on each axis
     */
    public static LocalPoint fromScene(int sceneX, int sceneY, WorldView view) {
        return new LocalPoint((sceneX << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE,
                (sceneY << LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE, view.getId());
    }

    /**
     * The scene tile this point is on.
     *
     * @return the scene x
     */
    public int getSceneX() {
        return x >> LOCAL_COORD_BITS;
    }

    /**
     * The scene tile this point is on.
     *
     * @return the scene y
     */
    public int getSceneY() {
        return y >> LOCAL_COORD_BITS;
    }

    /**
     * The straight-line distance to another point.
     *
     * @param other the other point
     * @return the distance in local units, rounded down; {@link Integer#MAX_VALUE} in another world view
     */
    public int distanceTo(LocalPoint other) {
        if (other.worldView != worldView) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.hypot(x - other.x, y - other.y);
    }

    /**
     * This point moved along x.
     *
     * @param dx local units to move east
     * @return the moved point
     */
    public LocalPoint dx(int dx) {
        return new LocalPoint(x + dx, y, worldView);
    }

    /**
     * This point moved along y.
     *
     * @param dy local units to move north
     * @return the moved point
     */
    public LocalPoint dy(int dy) {
        return new LocalPoint(x, y + dy, worldView);
    }

    /**
     * This point moved along both axes.
     *
     * @param dx local units to move east
     * @param dy local units to move north
     * @return the moved point
     */
    public LocalPoint plus(int dx, int dy) {
        return new LocalPoint(x + dx, y + dy, worldView);
    }
}
