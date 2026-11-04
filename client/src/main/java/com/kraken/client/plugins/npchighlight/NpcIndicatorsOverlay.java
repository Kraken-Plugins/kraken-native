package com.kraken.client.plugins.npchighlight;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.actor.NPC;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.gameval.VarbitID;
import com.kraken.client.ui.overlay.Overlay;
import com.kraken.client.ui.overlay.OverlayLayer;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.OverlayUtil;
import com.kraken.client.util.Text;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;

/**
 * Draws the highlighted NPCs' tile outlines and names, each in its own colours. The game draws the hull and outline
 * styles itself while the native hook is connected; without it, this outlines those NPCs' tiles instead. While the
 * draw list draws the tiles and names in the game's frames, this draws them only to compare, in magenta and unfilled.
 * Nothing is drawn during a cutscene. Runs on the client thread, like the plugin's event handlers, so it reads
 * {@link NpcIndicatorsPlugin#getHighlightedNpcs()} without locking.
 */
class NpcIndicatorsOverlay extends Overlay {

    private static final Color COMPARE = Color.MAGENTA;
    private static final Color NO_FILL = new Color(0, 0, 0, 0);

    private final Client client;
    private final NpcIndicatorsPlugin plugin;

    @Inject
    NpcIndicatorsOverlay(Client client, NpcIndicatorsPlugin plugin) {
        super(plugin);
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.getHighlightedNpcs().isEmpty() || client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 1) {
            return null;
        }
        boolean drawnInGame = plugin.isDrawnInGame();
        boolean inFrame = plugin.isDrawnInFrame();
        if (inFrame && !plugin.isComparing()) {
            return null;
        }
        for (HighlightedNpc highlighted : plugin.getHighlightedNpcs().values()) {
            render(graphics, highlighted, drawnInGame, inFrame);
        }
        return null;
    }

    private void render(Graphics2D graphics, HighlightedNpc highlighted, boolean drawnInGame, boolean compare) {
        NPC npc = highlighted.getNpc();
        NPCComposition composition = npc.getTransformedComposition();
        if (composition == null || !composition.isInteractible() || !highlighted.isShown()) {
            return;
        }
        Color border = compare ? COMPARE : highlighted.getHighlightColor();
        Color fill = compare ? NO_FILL : highlighted.getFillColor();
        Stroke stroke = new BasicStroke(highlighted.getBorderWidth());
        int size = composition.getSize();

        if (highlighted.isTile() || !drawnInGame && (highlighted.isHull() || highlighted.isOutline())) {
            renderPoly(graphics, npc.getCanvasTilePoly(), border, fill, stroke);
        }
        if (highlighted.isTrueTile()) {
            LocalPoint southWest = trueSouthWest(npc);
            if (southWest != null) {
                int toCentre = Perspective.LOCAL_TILE_SIZE * (size - 1) / 2;
                LocalPoint centre = southWest.plus(toCentre, toCentre);
                renderPoly(graphics, Perspective.getCanvasTileAreaPoly(client, centre, size), border, fill, stroke);
            }
        }
        if (highlighted.isSwTile()) {
            LocalPoint local = npc.getLocalLocation();
            if (local != null) {
                int toSouthWest = -((size - 1) * Perspective.LOCAL_TILE_SIZE / 2);
                LocalPoint southWest = local.plus(toSouthWest, toSouthWest);
                renderPoly(graphics, Perspective.getCanvasTilePoly(client, southWest), border, fill, stroke);
            }
        }
        if (highlighted.isSwTrueTile()) {
            LocalPoint southWest = trueSouthWest(npc);
            if (southWest != null) {
                renderPoly(graphics, Perspective.getCanvasTilePoly(client, southWest), border, fill, stroke);
            }
        }
        if (highlighted.isName()) {
            String name = Text.removeTags(npc.getName());
            if (!name.isEmpty()) {
                Point location = npc.getCanvasTextLocation(graphics, name, OverlayUtil.ACTOR_OVERHEAD_OFFSET);
                if (location != null) {
                    OverlayUtil.renderTextLocation(graphics, location, name, border);
                }
            }
        }
    }

    /**
     * The centre of the NPC's south-west tile as the server sees it.
     *
     * @param npc the NPC
     * @return the tile's centre, or null if its tile is unknown or outside the scene
     */
    private LocalPoint trueSouthWest(NPC npc) {
        WorldPoint tile = npc.getWorldLocation();
        return tile == null ? null : LocalPoint.fromWorld(client, tile);
    }

    private static void renderPoly(Graphics2D graphics, Shape shape, Color border, Color fill, Stroke stroke) {
        if (shape != null) {
            OverlayUtil.renderPolygon(graphics, shape, border, fill, stroke);
        }
    }
}
