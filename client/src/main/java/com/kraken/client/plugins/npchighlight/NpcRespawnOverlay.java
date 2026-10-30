package com.kraken.client.plugins.npchighlight;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.Locale;

/**
 * Marks where dead highlighted NPCs will respawn, with the seconds left until they do. Runs on the client thread,
 * like the plugin's event handlers, so it reads {@link NpcIndicatorsPlugin#getDeadNpcsToDisplay()} without locking.
 */
class NpcRespawnOverlay extends Overlay {

    /**
     * The countdown's colour: white reads best on the dark tile fill.
     */
    private static final Color TEXT_COLOR = Color.WHITE;

    /**
     * How long a game tick lasts, in seconds.
     */
    private static final double TICK_SECONDS = 0.6;

    private final Client client;
    private final NpcIndicatorsConfig config;
    private final NpcIndicatorsPlugin plugin;
    private final DecimalFormat timeLeft = new DecimalFormat("#0.0", DecimalFormatSymbols.getInstance(Locale.US));

    @Inject
    NpcRespawnOverlay(Client client, NpcIndicatorsConfig config, NpcIndicatorsPlugin plugin) {
        super(plugin);
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.getDeadNpcsToDisplay().isEmpty() || !config.showRespawnTimer()) {
            return null;
        }
        for (MemorizedNpc npc : plugin.getDeadNpcsToDisplay().values()) {
            render(graphics, npc);
        }
        return null;
    }

    private void render(Graphics2D graphics, MemorizedNpc npc) {
        if (npc.getPossibleRespawnLocations().isEmpty()) {
            return;
        }
        WorldPoint respawn = npc.getPossibleRespawnLocations().get(0);
        LocalPoint southWest = LocalPoint.fromWorld(client, respawn);
        if (southWest == null) {
            return;
        }
        int size = npc.getNpcSize();
        int toCentre = Perspective.LOCAL_TILE_SIZE * (size - 1) / 2;
        LocalPoint centre = southWest.plus(toCentre, toCentre);

        Polygon poly = Perspective.getCanvasTileAreaPoly(client, centre, size);
        if (poly != null) {
            OverlayUtil.renderPolygon(graphics, poly, config.highlightColor(), config.fillColor(),
                    new BasicStroke((float) config.borderWidth()));
        }

        Instant lastTick = plugin.getLastTickUpdate();
        double untilTick = (npc.getDiedOnTick() + npc.getRespawnTime() - client.getTickCount()) * TICK_SECONDS;
        double sinceTick = lastTick == null ? 0 : (Instant.now().toEpochMilli() - lastTick.toEpochMilli()) / 1000.0;
        String text = timeLeft.format(Math.max(0.0, untilTick - sinceTick));

        Point canvas = Perspective.localToCanvas(client, centre, respawn.getPlane());
        if (canvas != null) {
            int width = graphics.getFontMetrics().stringWidth(text);
            int height = graphics.getFontMetrics().getAscent();
            OverlayUtil.renderTextLocation(graphics, new Point(canvas.getX() - width / 2, canvas.getY() + height / 2),
                    text, TEXT_COLOR);
        }
    }
}
