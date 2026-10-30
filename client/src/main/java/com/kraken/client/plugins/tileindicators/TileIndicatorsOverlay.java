package com.kraken.client.plugins.tileindicators;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.gameval.VarbitID;
import com.kraken.api.scene.Tile;
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
 * Marks the tile under the mouse, the tile the player is walking to and the tile the player stands on, each as its
 * settings ask. Nothing is drawn during cutscenes. Runs on the client thread.
 */
class TileIndicatorsOverlay extends Overlay {

    private final Client client;
    private final TileIndicatorsConfig config;

    @Inject
    TileIndicatorsOverlay(TileIndicatorsPlugin plugin, Client client, TileIndicatorsConfig config) {
        super(plugin);
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(PRIORITY_MED);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 1) {
            return null;
        }
        if (config.highlightHoveredTile()) {
            Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
            if (tile != null) {
                renderTile(graphics, tile.getLocalLocation(), config.highlightHoveredColor(),
                        config.hoveredTileBorderWidth(), config.hoveredTileFillColor());
            }
        }
        if (config.highlightDestinationTile()) {
            renderTile(graphics, client.getLocalDestinationLocation(), config.highlightDestinationColor(),
                    config.destinationTileBorderWidth(), config.destinationTileFillColor());
        }
        if (config.highlightCurrentTile()) {
            Player player = client.getLocalPlayer();
            WorldPoint position = player == null ? null : player.getWorldLocation();
            if (position != null) {
                WorldView view = client.getTopLevelWorldView();
                renderTile(graphics, LocalPoint.fromWorld(view, position), config.highlightCurrentColor(),
                        config.currentTileBorderWidth(), config.currentTileFillColor());
            }
        }
        return null;
    }

    /**
     * Outlines and fills one tile.
     *
     * @param graphics    where to draw
     * @param tile        a point on the tile, or null to draw nothing
     * @param color       the outline colour
     * @param borderWidth the outline's width
     * @param fillColor   the fill colour
     */
    private void renderTile(Graphics2D graphics, LocalPoint tile, Color color, double borderWidth, Color fillColor) {
        if (tile == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, tile);
        if (poly != null) {
            OverlayUtil.renderPolygon(graphics, poly, color, fillColor, new BasicStroke((float) borderWidth));
        }
    }
}
