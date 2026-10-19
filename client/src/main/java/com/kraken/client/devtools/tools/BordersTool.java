package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.FineCoord;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Vector2;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.Ikon;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the borders of the world's grid cells around you on the ground: 8×8 zones or 64×64 map squares. Cells are
 * aligned to world tiles, so the map's base ({@code Scene.getBase}) places them in the scene.
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
        return new BordersTool("zoneBorders", "Zone borders", icon, "Outlines the 8×8 zones around you.", 8,
                new Color(240, 200, 80, 200));
    }

    /**
     * Map square borders.
     *
     * @param icon the sidebar icon
     * @return the tool
     */
    static BordersTool mapSquares(Ikon icon) {
        return new BordersTool("mapSquares", "Map squares", icon, "Outlines the 64×64 map squares around you.", 64,
                new Color(90, 200, 240, 220));
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    protected Marks read(KrakenApi api) {
        MapCoord self = api.client().coord();
        MapCoord base = api.scene().getBase();
        Projector projector = self == null || base == null ? null : Projector.of(api);
        if (projector == null) {
            return null;
        }
        Marks.Builder marks = new Marks.Builder(projector);
        int x1 = self.x() - RADIUS;
        int x2 = self.x() + RADIUS + 1;
        int z1 = self.z() - RADIUS;
        int z2 = self.z() + RADIUS + 1;
        for (int x = x1; x <= x2; x++) {
            if (Math.floorMod(x + base.x(), cell) == 0) {
                List<Vector2> line = new ArrayList<>();
                for (int z = z1; z <= z2; z++) {
                    line = extend(marks, line, projector.ground(self.level(), x * FineCoord.TILE, z * FineCoord.TILE, 0));
                }
                flush(marks, line);
            }
        }
        for (int z = z1; z <= z2; z++) {
            if (Math.floorMod(z + base.z(), cell) == 0) {
                List<Vector2> line = new ArrayList<>();
                for (int x = x1; x <= x2; x++) {
                    line = extend(marks, line, projector.ground(self.level(), x * FineCoord.TILE, z * FineCoord.TILE, 0));
                }
                flush(marks, line);
            }
        }
        return marks.build();
    }

    /**
     * Adds a point to the line being drawn; a point behind the camera ends it.
     *
     * @param marks the marks to add finished lines to
     * @param line  the points so far
     * @param point the next point, or null behind the camera
     * @return the line to continue
     */
    private List<Vector2> extend(Marks.Builder marks, List<Vector2> line, Vector2 point) {
        if (point == null) {
            flush(marks, line);
            return new ArrayList<>();
        }
        line.add(point);
        return line;
    }

    private void flush(Marks.Builder marks, List<Vector2> line) {
        if (line.size() >= 2) {
            marks.line(line.toArray(new Vector2[0]), color);
        }
    }

    @Override
    protected void paintOverlay(Marks marks, OverlayContext overlay) {
        marks.paint(overlay);
    }
}
