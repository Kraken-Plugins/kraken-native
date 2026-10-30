package com.kraken.client.plugins.groundmarkers;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.WorldView;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;

/**
 * Outlines and fills the marked tiles on the player's plane within {@link #MAX_DRAW_DISTANCE} tiles, and writes their
 * labels. Runs on the client thread.
 */
class GroundMarkerOverlay extends Overlay {

    /**
     * How far from the player, in tiles, marks are drawn.
     */
    private static final int MAX_DRAW_DISTANCE = 32;

    private final Client client;
    private final GroundMarkerConfig config;
    private final GroundMarkerPlugin plugin;

    @Inject
    GroundMarkerOverlay(Client client, GroundMarkerConfig config, GroundMarkerPlugin plugin) {
        super(plugin);
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_LOW);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        List<ColorTileMarker> points = plugin.getPoints();
        Player player = client.getLocalPlayer();
        if (points.isEmpty() || player == null) {
            return null;
        }
        WorldView view = client.getTopLevelWorldView();
        WorldPoint playerLocation = player.getWorldLocation();
        int plane = view.getPlane();
        Stroke stroke = new BasicStroke((float) config.borderWidth());
        Color fill = new Color(0, 0, 0, Math.max(0, Math.min(255, config.fillOpacity())));
        for (ColorTileMarker point : points) {
            WorldPoint worldPoint = point.getWorldPoint();
            if (worldPoint.getPlane() != plane
                    || playerLocation != null && worldPoint.distanceTo(playerLocation) >= MAX_DRAW_DISTANCE) {
                continue;
            }
            Color color = point.getColor() != null ? point.getColor() : config.markerColor();
            drawTile(graphics, view, worldPoint, color, fill, point.getLabel(), stroke);
        }
        return null;
    }

    /**
     * Draws one mark.
     *
     * @param graphics where to draw
     * @param view     the world view the tile is in
     * @param point    the tile
     * @param color    the outline and label colour
     * @param fill     the fill colour
     * @param label    the label, or null for none
     * @param stroke   the outline's line
     */
    private void drawTile(Graphics2D graphics, WorldView view, WorldPoint point, Color color, Color fill,
                          String label, Stroke stroke) {
        LocalPoint local = LocalPoint.fromWorld(view, point);
        if (local == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, local);
        if (poly != null) {
            OverlayUtil.renderPolygon(graphics, poly, color, fill, stroke);
        }
        if (label != null && !label.isEmpty()) {
            Point text = Perspective.getCanvasTextLocation(client, graphics, local, label, 0);
            if (text != null) {
                OverlayUtil.renderTextLocation(graphics, text, label, color);
            }
        }
    }
}
