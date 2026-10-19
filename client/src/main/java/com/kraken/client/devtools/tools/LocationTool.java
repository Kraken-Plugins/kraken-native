package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Client;
import com.kraken.api.game.MapCoord;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.FactsView;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where you're standing, as an info box over the game and in a window. The client keeps tiles relative to the loaded
 * map's base (the scene tile); adding the base gives the world tile, from which the map square (64×64 region) and zone
 * (8×8) follow.
 */
final class LocationTool extends DevTool<LocationTool.Where> {
    record Where(MapCoord scene, MapCoord world, MapCoord base) {
    }

    private final ToolOption tile = option("tile", "World tile", true);
    private final ToolOption sceneTile = option("sceneTile", "Scene tile", false);
    private final ToolOption mapSquare = option("mapSquare", "Map square", true);
    private final ToolOption mapSquareTile = option("mapSquareTile", "Tile in map square", true);
    private final ToolOption zone = option("zone", "Zone", false);

    LocationTool() {
        super("location", "Location", Category.MAP, MaterialDesignM.MAP_MARKER_OUTLINE,
                "Your world tile, your tile in the loaded scene, the map square (64×64 region) and zone (8×8) you're in, "
                        + "and your tile within the map square.");
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
        MapCoord at = localTile(client);
        if (at == null) {
            return null;
        }
        MapCoord base = api.scene().getBase();
        return new Where(at, base == null ? null : api.scene().toWorld(at), base);
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
        MapCoord world = where.world();
        if (!onlyEnabled || tile.isOn()) {
            rows.put("Tile", world == null ? "?" : world.x() + ", " + world.z() + ", " + world.level());
        }
        if (!onlyEnabled || sceneTile.isOn()) {
            MapCoord at = where.scene();
            rows.put("Scene tile", at.x() + ", " + at.z() + ", " + at.level());
        }
        if (!onlyEnabled) {
            MapCoord base = where.base();
            rows.put("Scene base", base == null ? "?" : base.x() + ", " + base.z());
        }
        if (world == null) {
            return rows;
        }
        int squareX = world.x() >> 6;
        int squareZ = world.z() >> 6;
        if (!onlyEnabled || mapSquare.isOn()) {
            rows.put("Map square", squareX + ", " + squareZ + " (" + (squareX << 8 | squareZ) + ")");
        }
        if (!onlyEnabled || mapSquareTile.isOn()) {
            rows.put("In square", (world.x() & 63) + ", " + (world.z() & 63));
        }
        if (!onlyEnabled || zone.isOn()) {
            rows.put("Zone", (world.x() >> 3) + ", " + (world.z() >> 3));
        }
        return rows;
    }
}
