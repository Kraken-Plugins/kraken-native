package com.kraken.client.plugins.npchighlight;

import com.google.inject.Inject;
import com.kraken.api.actor.NPC;
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

/**
 * Outlines the highlighted NPCs' tiles, and writes their names over them if the setting is on. Runs on the client
 * thread, like the plugin's event handlers, so it reads {@link NpcHighlightPlugin#getHighlighted()} without locking.
 */
class NpcHighlightOverlay extends Overlay {

    private final NpcHighlightPlugin plugin;
    private final NpcHighlightConfig config;

    @Inject
    NpcHighlightOverlay(NpcHighlightPlugin plugin, NpcHighlightConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Color colour = config.colour();
        // The settings page keeps the width in range; a hand-edited settings file might not
        Stroke stroke = new BasicStroke(Math.max(1, config.width()));
        boolean showNames = config.showNames();
        for (NPC npc : plugin.getHighlighted()) {
            Polygon tile = npc.getCanvasTilePoly();
            if (tile != null) {
                OverlayUtil.renderPolygon(graphics, tile, colour, stroke);
            }
            if (showNames) {
                OverlayUtil.renderActorTextOverlay(graphics, npc, npc.getName(), colour);
            }
        }
        return null;
    }
}
