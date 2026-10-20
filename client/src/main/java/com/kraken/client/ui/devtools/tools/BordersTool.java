package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.Scene;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.Ikon;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the borders of the world's grid cells around you on the ground: 8x8 zones or 64x64 map squares. Cells are
 * aligned to world tiles, so the scene's base ({@link Scene#getBaseX()}, {@link Scene#getBaseY()}) places them in the
 * scene.
 */
final class BordersTool extends DevTool<Marks> {
    private static final int RADIUS = 24;

    private final int cell;
    private final Color color;

    private BordersTool(String id, String name, Ikon icon, String description, int cell, Color color) {
        super(id, name, Category.MAP, icon, description);
        this.cell = cell;
        this.color = color;
    }

    /**
     * Zone borders.
     *
     * @param icon the sidebar icon
     * @return the tool
     */
    static BordersTool zones(Ikon icon) {
        return new BordersTool("zoneBorders", "Zone borders", icon, "Outlines the 8x8 zones around you.", 8,
                new Color(240, 200, 80, 200));
    }

    /**
     * Map square borders.
     *
     * @param icon the sidebar icon
     * @return the tool
     */
    static BordersTool mapSquares(Ikon icon) {
        return new BordersTool("mapSquares", "Map squares", icon, "Outlines the 64x64 map squares around you.", 64,
                new Color(90, 200, 240, 220));
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    protected Marks read(GameSession session) {
        Client client = session.client();
        WorldPoint self = localTile(client);
        Scene scene = client.getScene();
        Projector projector = self == null || scene.getBaseX() < 0 ? null : Projector.of(session);
        if (projector == null) {
            return null;
        }
        Marks.Builder marks = new Marks.Builder(projector);
        int plane = self.getPlane();
        int x1 = self.getX() - RADIUS;
        int x2 = self.getX() + RADIUS + 1;
        int z1 = self.getY() - RADIUS;
        int z2 = self.getY() + RADIUS + 1;
        for (int x = x1; x <= x2; x++) {
            if (Math.floorMod(x, cell) == 0) {
                List<Point> line = new ArrayList<>();
                for (int z = z1; z <= z2; z++) {
                    line = extend(marks, line, point(projector, scene, plane, x, z));
                }
                flush(marks, line);
            }
        }
        for (int z = z1; z <= z2; z++) {
            if (Math.floorMod(z, cell) == 0) {
                List<Point> line = new ArrayList<>();
                for (int x = x1; x <= x2; x++) {
                    line = extend(marks, line, point(projector, scene, plane, x, z));
                }
                flush(marks, line);
            }
        }
        return marks.build();
    }

    /**
     * Projects the ground at a world tile's corner.
     *
     * @param projector the frame's projection
     * @param scene     the scene, for its base
     * @param plane     the plane
     * @param worldX    the world x of the corner
     * @param worldY    the world y of the corner
     * @return the screen point, or null behind the camera
     */
    private static Point point(Projector projector, Scene scene, int plane, int worldX, int worldY) {
        return projector.ground(plane, (worldX - scene.getBaseX()) * Perspective.LOCAL_TILE_SIZE,
                (worldY - scene.getBaseY()) * Perspective.LOCAL_TILE_SIZE, 0);
    }

    /**
     * Adds a point to the line being drawn; a point behind the camera ends it.
     *
     * @param marks the marks to add finished lines to
     * @param line  the points so far
     * @param point the next point, or null behind the camera
     * @return the line to continue
     */
    private List<Point> extend(Marks.Builder marks, List<Point> line, Point point) {
        if (point == null) {
            flush(marks, line);
            return new ArrayList<>();
        }
        line.add(point);
        return line;
    }

    /**
     * Draws a line if it has at least two points.
     *
     * @param marks the marks to add it to
     * @param line  the points
     */
    private void flush(Marks.Builder marks, List<Point> line) {
        if (line.size() >= 2) {
            marks.line(line.toArray(new Point[0]), color);
        }
    }

    @Override
    protected void paintOverlay(Marks marks, OverlayContext overlay) {
        marks.paint(overlay);
    }
}
