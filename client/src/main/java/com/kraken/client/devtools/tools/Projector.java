package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.Graphics;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.Vector2;

import java.util.HashMap;
import java.util.Map;

/**
 * Projects scene positions to the screen for one overlay frame, on the poll thread: the camera is read once, and
 * ground heights are cached so neighbouring tiles share their corners.
 */
final class Projector {

    /**
     * How far above the ground an actor's label goes (a little over a player's height).
     */
    static final int LABEL_LIFT = 200;

    private final Graphics.Projection projection;
    private final Scene scene;
    private final Vector2 clientSize;
    private final Map<Long, Integer> heights = new HashMap<>();

    private Projector(Graphics.Projection projection, Scene scene, Vector2 clientSize) {
        this.projection = projection;
        this.scene = scene;
        this.clientSize = clientSize;
    }

    /**
     * Reads this frame's projection.
     *
     * @param api the attached API
     * @return a projector, or null if the camera or the client's size can't be read
     */
    static Projector of(KrakenApi api) {
        Graphics.Projection projection = api.lua().graphics().projection();
        Vector2 size = api.lua().graphics().clientDimensions();
        return projection == null || size == null ? null : new Projector(projection, api.lua().scene(), size);
    }

    /**
     * The client's size the points were projected for.
     *
     * @return the client's size in its own pixels
     */
    Vector2 clientSize() {
        return clientSize;
    }

    /**
     * Projects a fine coordinate.
     *
     * @param coord the point, with its height in {@code y}
     * @return the screen point in client pixels, or null behind the camera
     */
    Vector2 point(FineCoord coord) {
        return projection.project(coord.x(), coord.y(), coord.z());
    }

    /**
     * Projects a point some way above another.
     *
     * @param coord the base point
     * @param lift  how far up (positive is higher)
     * @return the screen point, or null if the base is missing or behind the camera
     */
    Vector2 above(FineCoord coord, int lift) {
        return coord == null || coord.isMissing() ? null : projection.project(coord.x(), coord.y() - lift, coord.z());
    }

    /**
     * Projects a point on the ground.
     *
     * @param level the level
     * @param fineX the fine x
     * @param fineZ the fine z
     * @param lift  how far above the ground (positive is higher)
     * @return the screen point, or null behind the camera
     */
    Vector2 ground(int level, int fineX, int fineZ, int lift) {
        long key = ((long) level << 60) ^ ((long) fineX << 30) ^ (fineZ & 0x3fffffffL);
        int height = heights.computeIfAbsent(key, k -> scene.getGroundHeight(level, fineX, fineZ));
        return projection.project(fineX, height - lift, fineZ);
    }

    /**
     * Projects a tile's centre on the ground.
     *
     * @param tile the scene tile
     * @param lift how far above the ground
     * @return the screen point, or null behind the camera
     */
    Vector2 tileCenter(MapCoord tile, int lift) {
        return ground(tile.level(), tile.x() * FineCoord.TILE + FineCoord.TILE / 2,
                tile.z() * FineCoord.TILE + FineCoord.TILE / 2, lift);
    }

    /**
     * Projects a tile's outline on the ground.
     *
     * @param tile the scene tile
     * @return the south-west, south-east, north-east and north-west corners, or null if any is behind the camera
     */
    Vector2[] tile(MapCoord tile) {
        return area(tile, 1, 1);
    }

    /**
     * Projects the outline of a rectangle of tiles on the ground, corner points only.
     *
     * @param tile   the south-west tile
     * @param width  tiles along x
     * @param length tiles along z
     * @return the four corners, or null if any is behind the camera
     */
    Vector2[] area(MapCoord tile, int width, int length) {
        int x = tile.x() * FineCoord.TILE;
        int z = tile.z() * FineCoord.TILE;
        int dx = width * FineCoord.TILE;
        int dz = length * FineCoord.TILE;
        Vector2[] corners = {ground(tile.level(), x, z, 0), ground(tile.level(), x + dx, z, 0),
                ground(tile.level(), x + dx, z + dz, 0), ground(tile.level(), x, z + dz, 0)};
        for (Vector2 corner : corners) {
            if (corner == null) {
                return null;
            }
        }
        return corners;
    }

    /**
     * Projects one edge of a tile on the ground.
     *
     * @param tile the scene tile
     * @param side 0 south, 1 east, 2 north, 3 west
     * @return the edge's two ends, or null if either is behind the camera
     */
    Vector2[] edge(MapCoord tile, int side) {
        Vector2[] corners = tile(tile);
        return corners == null ? null : new Vector2[]{corners[side], corners[(side + 1) % 4]};
    }
}
