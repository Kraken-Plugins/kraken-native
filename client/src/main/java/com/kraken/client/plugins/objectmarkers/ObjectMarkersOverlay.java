package com.kraken.client.plugins.objectmarkers;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.TileObject;
import com.kraken.api.WorldView;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Outlines the marked objects on the plane you're on (a game object's whole footprint), with their names if the
 * setting is on. Runs on the client thread, like the plugin's event handlers, so it reads
 * {@link ObjectMarkersPlugin#getMarked()} without locking.
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
        for (TileObject object : plugin.getMarked()) {
            if (object.getPlane() == plane) {
                OverlayUtil.renderTileOverlay(graphics, object, showNames ? plugin.nameOf(object) : null, colour);
            }
        }
        return null;
    }
}
