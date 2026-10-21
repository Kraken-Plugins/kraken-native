package com.kraken.client.plugins.tilemarkers;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
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

/**
 * Outlines the marked tiles on the plane you're on while the game can't (the native hook isn't connected). With the
 * hook, the game draws them and this draws nothing.
 */
class TileMarkersOverlay extends Overlay {

    private final TileMarkersPlugin plugin;
    private final TileMarkersConfig config;
    private final Client client;

    @Inject
    TileMarkersOverlay(TileMarkersPlugin plugin, TileMarkersConfig config, Client client) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.isDrawnInGame() || plugin.getTiles().isEmpty()) {
            return null;
        }
        WorldView view = client.getTopLevelWorldView();
        if (view == null) {
            return null;
        }
        int plane = view.getPlane();
        Color colour = config.colour();
        Color outline = new Color(colour.getRGB() | 0xff000000, true);
        Color fill = config.fill() ? colour : new Color(0, 0, 0, 0);
        BasicStroke stroke = new BasicStroke(Math.max(1, config.width()));
        for (WorldPoint tile : plugin.getTiles()) {
            if (tile.getPlane() != plane) {
                continue;
            }
            LocalPoint local = LocalPoint.fromWorld(view, tile);
            Polygon poly = local == null ? null : Perspective.getCanvasTilePoly(client, local);
            if (poly != null) {
                OverlayUtil.renderPolygon(graphics, poly, outline, fill, stroke);
            }
        }
        return null;
    }
}
