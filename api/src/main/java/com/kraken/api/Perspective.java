package com.kraken.api;

import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.WorldView;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * Scene geometry: constants for local units (1/128 of a tile, relative to the scene's south-west corner), and helpers
 * that place scene points on the game canvas.
 *
 * <p>Canvas positions are in the client's own (DPI-scaled) pixels, {@link Client#getCanvasWidth()} by
 * {@link Client#getCanvasHeight()}: the units an overlay's {@code render} draws in. The helpers return null for points
 * behind the camera.
 *
 * <p>Each call reads the camera once ({@link Client#getCameraProjection()}) plus a ground height per point, all from
 * the game process: a tile outline costs a few dozen reads. Code projecting many points for one frame can read the
 * projection once and call {@link CameraProjection#project} itself.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Perspective {

    /**
     * Bits of a local coordinate below the tile: a local coordinate shifted right by this is a scene tile.
     */
    public static final int LOCAL_COORD_BITS = 7;

    /**
     * Local units per tile.
     */
    public static final int LOCAL_TILE_SIZE = 1 << LOCAL_COORD_BITS;

    /**
     * Local units per half tile: a tile's centre is its corner plus this.
     */
    public static final int LOCAL_HALF_TILE_SIZE = LOCAL_TILE_SIZE / 2;

    /**
     * A normal scene's size in tiles along each axis.
     */
    public static final int SCENE_SIZE = 104;

    /**
     * Where a point on the ground appears on the canvas.
     *
     * @param client the client
     * @param point  the point
     * @param plane  the plane whose ground it is on
     * @return the canvas position, or null if the point is outside its world view's scene (or the view isn't loaded)
     *         or behind the camera
     */
    public static Point localToCanvas(Client client, LocalPoint point, int plane) {
        return localToCanvas(client, point, plane, 0);
    }

    /**
     * Where a point some way above the ground appears on the canvas.
     *
     * @param client  the client
     * @param point   the point
     * @param plane   the plane whose ground it is on
     * @param zOffset how far above the ground, in local units
     * @return the canvas position, or null if the point is outside its world view's scene (or the view isn't loaded)
     *         or behind the camera
     */
    public static Point localToCanvas(Client client, LocalPoint point, int plane, int zOffset) {
        WorldView view = client.getWorldView(point.getWorldView());
        if (view == null || !view.contains(point)) {
            return null;
        }
        CameraProjection projection = client.getCameraProjection();
        if (projection == null) {
            return null;
        }
        int height = view.getTileHeight(point.getX(), point.getY(), plane);
        return projection.project(point.getX(), point.getY(), height - zOffset);
    }

    /**
     * Where a point of the top-level scene, height included, appears on the canvas.
     *
     * @param client the client
     * @param x      the local x
     * @param y      the local y
     * @param z      the height (more negative is higher), e.g. {@link #getTileHeight} minus a lift
     * @return the canvas position, or null if it is behind the camera or the camera can't be read
     */
    public static Point localToCanvas(Client client, int x, int y, int z) {
        CameraProjection projection = client.getCameraProjection();
        return projection == null ? null : projection.project(x, y, z);
    }

    /**
     * The ground's height at a point, as the client places actors and the scene's tiles: bilinear between the tile's
     * corner heights, taken from the level above under a bridge.
     *
     * @param client the client
     * @param point  the point
     * @param plane  the plane
     * @return the height (more negative is higher); 0 outside the scene, for a world view that isn't loaded, or if it
     *         can't be read
     */
    public static int getTileHeight(Client client, LocalPoint point, int plane) {
        WorldView view = client.getWorldView(point.getWorldView());
        return view == null ? 0 : view.getTileHeight(point.getX(), point.getY(), plane);
    }

    /**
     * The outline of the tile a point is on, on its world view's plane.
     *
     * @param client the client
     * @param point  a point on the tile (the outline is centred on it, so pass the tile's centre)
     * @return the outline, or null if the point is outside the scene or a corner is behind the camera
     */
    public static Polygon getCanvasTilePoly(Client client, LocalPoint point) {
        return getCanvasTileAreaPoly(client, point, 1);
    }

    /**
     * The outline of the tile a point is on, raised off the ground.
     *
     * @param client  the client
     * @param point   a point on the tile (the outline is centred on it)
     * @param zOffset how far above the ground, in local units
     * @return the outline, or null if the point is outside the scene or a corner is behind the camera
     */
    public static Polygon getCanvasTilePoly(Client client, LocalPoint point, int zOffset) {
        WorldView view = client.getWorldView(point.getWorldView());
        return view == null ? null : getCanvasTileAreaPoly(client, point, 1, 1, view.getPlane(), zOffset);
    }

    /**
     * The outline of a square of tiles centred on a point, on its world view's plane: an NPC's footprint, say.
     *
     * @param client the client
     * @param point  the square's centre
     * @param size   the square's size in tiles
     * @return the outline, or null if the point is outside the scene or a corner is behind the camera
     */
    public static Polygon getCanvasTileAreaPoly(Client client, LocalPoint point, int size) {
        WorldView view = client.getWorldView(point.getWorldView());
        return view == null ? null : getCanvasTileAreaPoly(client, point, size, size, view.getPlane(), 0);
    }

    /**
     * The outline of a rectangle of tiles centred on a point, following the ground.
     *
     * <p>Corners on or past the scene's far edges take the ground height just inside the scene, since the ground is
     * only read for the scene's tiles. Each corner's height checks the bridge flag of the tile the corner is on.
     *
     * @param client  the client
     * @param point   the rectangle's centre
     * @param sizeX   its width in tiles
     * @param sizeY   its length in tiles
     * @param plane   the plane whose ground it follows
     * @param zOffset how far above the ground, in local units
     * @return the south-west, south-east, north-east and north-west corners on the canvas; null if the centre is
     *         outside the scene or a corner is behind the camera
     */
    public static Polygon getCanvasTileAreaPoly(Client client, LocalPoint point, int sizeX, int sizeY, int plane,
                                                int zOffset) {
        WorldView view = client.getWorldView(point.getWorldView());
        if (view == null || !view.contains(point)) {
            return null;
        }
        CameraProjection projection = client.getCameraProjection();
        if (projection == null) {
            return null;
        }
        int westX = point.getX() - sizeX * LOCAL_TILE_SIZE / 2;
        int southY = point.getY() - sizeY * LOCAL_TILE_SIZE / 2;
        int eastX = point.getX() + sizeX * LOCAL_TILE_SIZE / 2;
        int northY = point.getY() + sizeY * LOCAL_TILE_SIZE / 2;
        int[][] corners = {{westX, southY}, {eastX, southY}, {eastX, northY}, {westX, northY}};
        int lastX = view.getSizeX() * LOCAL_TILE_SIZE - 1;
        int lastY = view.getSizeY() * LOCAL_TILE_SIZE - 1;
        Polygon polygon = new Polygon();
        for (int[] corner : corners) {
            int groundX = Math.max(0, Math.min(corner[0], lastX));
            int groundY = Math.max(0, Math.min(corner[1], lastY));
            int height = view.getTileHeight(groundX, groundY, plane) - zOffset;
            Point canvas = projection.project(corner[0], corner[1], height);
            if (canvas == null) {
                return null;
            }
            polygon.addPoint(canvas.getX(), canvas.getY());
        }
        return polygon;
    }

    /**
     * Where to draw text centred over a point, on its world view's plane.
     *
     * @param client   the client
     * @param graphics the graphics the text will be drawn with (its font sets the width)
     * @param point    the point
     * @param text     the text
     * @param zOffset  how far above the ground, in local units
     * @return the baseline's left end, or null if the text is null, the point is outside its world view's scene or it
     *         is behind the camera
     */
    public static Point getCanvasTextLocation(Client client, Graphics2D graphics, LocalPoint point, String text,
                                              int zOffset) {
        if (text == null) {
            return null;
        }
        WorldView view = client.getWorldView(point.getWorldView());
        Point at = view == null ? null : localToCanvas(client, point, view.getPlane(), zOffset);
        if (at == null) {
            return null;
        }
        FontMetrics metrics = graphics.getFontMetrics();
        Rectangle2D bounds = metrics.getStringBounds(text, graphics);
        return new Point(at.getX() - (int) (bounds.getWidth() / 2), at.getY());
    }

    /**
     * Where to draw an image centred over a point, on its world view's plane.
     *
     * @param client  the client
     * @param point   the point
     * @param image   the image
     * @param zOffset how far above the ground, in local units
     * @return the image's top-left corner, or null if the point is outside its world view's scene or behind the camera
     */
    public static Point getCanvasImageLocation(Client client, LocalPoint point, BufferedImage image, int zOffset) {
        WorldView view = client.getWorldView(point.getWorldView());
        Point at = view == null ? null : localToCanvas(client, point, view.getPlane(), zOffset);
        if (at == null) {
            return null;
        }
        return new Point(at.getX() - image.getWidth() / 2, at.getY() - image.getHeight() / 2);
    }
}
