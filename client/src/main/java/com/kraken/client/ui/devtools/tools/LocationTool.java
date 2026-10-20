package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.Scene;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.FactsView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where you're standing, as an info box over the game and in a window: your world tile, the map square (64x64 region)
 * and zone (8x8) you're in, your tile within the map square, and your position in the loaded scene.
 */
final class LocationTool extends DevTool<LocationTool.Where> {
    /**
     * One read of where the local player is.
     *
     * @param world    the world tile, or null if you aren't loaded
     * @param local    the local point, or null
     * @param baseX    the scene's base x (its south-west tile's world x), or -1 if unreadable
     * @param baseY    the scene's base y, or -1 if unreadable
     */
    record Where(WorldPoint world, LocalPoint local, int baseX, int baseY) {
    }

    private final ToolOption tile = option("tile", "World tile", true);
    private final ToolOption sceneTile = option("sceneTile", "Scene tile", false);
    private final ToolOption mapSquare = option("mapSquare", "Map square", true);
    private final ToolOption mapSquareTile = option("mapSquareTile", "Tile in map square", true);
    private final ToolOption zone = option("zone", "Zone", false);

    LocationTool() {
        super("location", "Location", Category.MAP, MaterialDesignM.MAP_MARKER_OUTLINE,
                "Your world tile, the map square (64x64 region) and zone (8x8) you're in, your tile within the map "
                        + "square, and your position in the loaded scene.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected Where read(KrakenApi api) {
        Client client = api.client();
        Player self = client.getLocalPlayer();
        if (self == null) {
            return null;
        }
        Scene scene = client.getScene();
        return new Where(self.getWorldLocation(), self.getLocalLocation(), scene.getBaseX(), scene.getBaseY());
    }

    @Override
    protected void paintOverlay(Where where, OverlayContext overlay) {
        overlay.infoPanel("Location", facts(where, true));
    }

    @Override
    protected ToolView<Where> createView() {
        return new FactsView<>(where -> facts(where, false));
    }

    /**
     * The rows to show.
     *
     * @param where       where you are
     * @param onlyEnabled apply the options (the window always shows everything)
     * @return the rows, in order
     */
    private Map<String, String> facts(Where where, boolean onlyEnabled) {
        Map<String, String> rows = new LinkedHashMap<>();
        WorldPoint world = where.world();
        if (!onlyEnabled || tile.isOn()) {
            rows.put("Tile", world == null ? "?" : world.getX() + ", " + world.getY() + ", " + world.getPlane());
        }
        if (!onlyEnabled || sceneTile.isOn()) {
            LocalPoint local = where.local();
            rows.put("Scene tile", local == null ? "?"
                    : local.getSceneX() + ", " + local.getSceneY() + ", " + (world == null ? "?" : world.getPlane()));
            rows.put("Local point", local == null ? "?" : local.getX() + ", " + local.getY());
        }
        if (!onlyEnabled) {
            rows.put("Scene base", where.baseX() < 0 ? "?" : where.baseX() + ", " + where.baseY());
        }
        if (world == null) {
            return rows;
        }
        int squareX = world.getX() >> 6;
        int squareZ = world.getY() >> 6;
        if (!onlyEnabled || mapSquare.isOn()) {
            rows.put("Map square", squareX + ", " + squareZ + " (" + world.getRegionID() + ")");
        }
        if (!onlyEnabled || mapSquareTile.isOn()) {
            rows.put("In square", (world.getX() & 63) + ", " + (world.getY() & 63));
        }
        if (!onlyEnabled || zone.isOn()) {
            rows.put("Zone", (world.getX() >> 3) + ", " + (world.getY() >> 3));
        }
        return rows;
    }
}
