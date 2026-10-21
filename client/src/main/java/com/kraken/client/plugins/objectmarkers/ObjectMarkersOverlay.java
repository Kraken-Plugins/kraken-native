package com.kraken.client.plugins.objectmarkers;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Point;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WorldView;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Writes the marked objects' names on them if the setting is on, and outlines their tiles (a game object's whole
 * footprint) on the plane you're on while the game can't (the native hook isn't connected). Runs on the client thread,
 * like the plugin's event handlers, so it reads {@link ObjectMarkersPlugin#getMarked()} without locking.
 */
class ObjectMarkersOverlay extends Overlay {

    private final ObjectMarkersPlugin plugin;
    private final ObjectMarkersConfig config;
    private final Client client;

    @Inject
    ObjectMarkersOverlay(ObjectMarkersPlugin plugin, ObjectMarkersConfig config, Client client) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        WorldView view = client.getTopLevelWorldView();
        if (view == null || plugin.getMarked().isEmpty()) {
            return null;
        }
        int plane = view.getPlane();
        Color colour = config.colour();
        boolean showNames = config.showNames();
        boolean outlineTiles = !plugin.isDrawnInGame();
        if (!outlineTiles && !showNames) {
            return null;
        }
        for (TileObject object : plugin.getMarked()) {
            if (object.getPlane() != plane) {
                continue;
            }
            String name = showNames ? plugin.nameOf(object) : null;
            if (outlineTiles) {
                OverlayUtil.renderTileOverlay(graphics, object, name, colour);
            } else {
                Point location = object.getCanvasTextLocation(graphics, name, 0);
                if (location != null) {
                    OverlayUtil.renderTextLocation(graphics, location, name, colour);
                }
            }
        }
        return null;
    }
}
