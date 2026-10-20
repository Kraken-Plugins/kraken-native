package com.kraken.client.ui.devtools.tools;

import com.kraken.api.CameraProjection;
import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.WorldView;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Projects scene positions to the screen for one overlay frame, on the poll thread: the camera is read once, and
 * ground heights are cached so neighbouring tiles share their corners. Points are local coordinates of the top-level
 * scene (128 per tile, relative to the scene's south-west corner), with height as {@link WorldView#getTileHeight}
 * gives it (more negative is higher).
 */
final class Projector {

    /**
     * How far above the ground an actor's label goes (a little over a player's height).
     */
    static final int LABEL_LIFT = 200;

    private final CameraProjection projection;
    private final WorldView view;
    private final Dimension clientSize;
    private final Map<Long, Integer> heights = new HashMap<>();

    private Projector(CameraProjection projection, WorldView view, Dimension clientSize) {
        this.projection = projection;
        this.view = view;
        this.clientSize = clientSize;
    }

    /**
     * Reads this frame's camera and the top-level world.
     *
     * @param api the attached API
     * @return a projector, or null if the camera or the client's size can't be read
     */
    static Projector of(KrakenApi api) {
        Client client = api.client();
        CameraProjection projection = client.getCameraProjection();
        WorldView view = client.getTopLevelWorldView();
        Dimension size = new Dimension(client.getCanvasWidth(), client.getCanvasHeight());
        return projection == null || view == null ? null : new Projector(projection, view, size);
    }

    /**
     * The client's size the points were projected for.
     *
     * @return the client's size in its own pixels
     */
    Dimension clientSize() {
        return clientSize;
    }

    /**
     * The top-level world the points are projected in.
     *
     * @return the view
     */
    WorldView view() {
        return view;
    }

    /**
     * Projects a point in the air, at a position the client reads directly (a projectile's, say).
     *
     * @param x the local x
     * @param y the local y (north)
     * @param z the height (more negative is higher)
     * @return the screen point in client pixels, or null behind the camera
     */
    Point at(int x, int y, int z) {
        return projection.project(x, y, z);
    }

    /**
     * Projects a point on the ground of the top-level scene, lifted some way above it.
     *
     * @param plane the plane whose ground it is on
     * @param x     the local x
     * @param y     the local y (north)
     * @param lift  how far above the ground (positive is higher)
     * @return the screen point in client pixels, or null behind the camera
     */
    Point ground(int plane, int x, int y, int lift) {
        long key = ((long) plane << 60) ^ ((long) x << 30) ^ (y & 0x3fffffffL);
        int height = heights.computeIfAbsent(key, k -> view.getTileHeight(x, y, plane));
        return projection.project(x, y, height - lift);
    }

    /**
     * Projects a point an actor stands on, some way above the ground.
     *
     * @param plane the plane the actor is on
     * @param at    the actor's local location, or null
     * @param lift  how far above the ground (positive is higher)
     * @return the screen point, or null if the location is missing or behind the camera
     */
    Point above(int plane, LocalPoint at, int lift) {
        return at == null ? null : ground(plane, at.getX(), at.getY(), lift);
    }

    /**
     * Projects a tile's centre on the ground.
     *
     * @param plane  the plane
     * @param sceneX the scene x of the tile
     * @param sceneY the scene y of the tile
     * @param lift   how far above the ground
     * @return the screen point, or null behind the camera
     */
    Point tileCenter(int plane, int sceneX, int sceneY, int lift) {
        return ground(plane, sceneX * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_HALF_TILE_SIZE,
                sceneY * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_HALF_TILE_SIZE, lift);
    }

    /**
     * Projects a tile's outline on the ground.
     *
     * @param plane  the plane
     * @param sceneX the scene x of the tile
     * @param sceneY the scene y of the tile
     * @return the south-west, south-east, north-east and north-west corners, or null if any is behind the camera
     */
    Point[] tile(int plane, int sceneX, int sceneY) {
        return area(plane, sceneX, sceneY, 1, 1);
    }

    /**
     * Projects the outline of a rectangle of tiles on the ground, corner points only.
     *
     * @param plane  the plane
     * @param sceneX the scene x of the south-west tile
     * @param sceneY the scene y of the south-west tile
     * @param width  tiles along x
     * @param length tiles along y
     * @return the four corners, or null if any is behind the camera
     */
    Point[] area(int plane, int sceneX, int sceneY, int width, int length) {
        int x = sceneX * Perspective.LOCAL_TILE_SIZE;
        int y = sceneY * Perspective.LOCAL_TILE_SIZE;
        return corners(plane, x, y, x + width * Perspective.LOCAL_TILE_SIZE, y + length * Perspective.LOCAL_TILE_SIZE);
    }

    /**
     * Projects the outline of the tiles an actor stands on, centred on its footprint's centre.
     *
     * @param plane  the plane the actor is on
     * @param centre the actor's local location (the centre of its footprint), or null
     * @param size   the footprint's width and length in tiles
     * @return the four corners, or null if the location is missing or any corner is behind the camera
     */
    Point[] footprint(int plane, LocalPoint centre, int size) {
        if (centre == null) {
            return null;
        }
        int half = size * Perspective.LOCAL_TILE_SIZE / 2;
        return corners(plane, centre.getX() - half, centre.getY() - half, centre.getX() + half,
                centre.getY() + half);
    }

    /**
     * Projects the four corners of a rectangle on the ground.
     *
     * @param plane the plane
     * @param x0    the local x of the south-west corner
     * @param y0    the local y of the south-west corner
     * @param x1    the local x of the north-east corner
     * @param y1    the local y of the north-east corner
     * @return the south-west, south-east, north-east and north-west corners, or null if any is behind the camera
     */
    private Point[] corners(int plane, int x0, int y0, int x1, int y1) {
        Point[] corners = {ground(plane, x0, y0, 0), ground(plane, x1, y0, 0),
                ground(plane, x1, y1, 0), ground(plane, x0, y1, 0)};
        for (Point corner : corners) {
            if (corner == null) {
                return null;
            }
        }
        return corners;
    }

    /**
     * Projects one edge of a tile on the ground.
     *
     * @param plane  the plane
     * @param sceneX the scene x of the tile
     * @param sceneY the scene y of the tile
     * @param side   0 south, 1 east, 2 north, 3 west
     * @return the edge's two ends, or null if either is behind the camera
     */
    Point[] edge(int plane, int sceneX, int sceneY, int side) {
        Point[] corners = tile(plane, sceneX, sceneY);
        return corners == null ? null : new Point[]{corners[side], corners[(side + 1) % 4]};
    }
}
