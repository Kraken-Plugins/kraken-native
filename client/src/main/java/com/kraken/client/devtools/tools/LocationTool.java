package com.kraken.client.devtools.tools;

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
 * Where you're standing, as an info box over the game and in a window. A map square (region) is 64×64 tiles and a
 * zone is 8×8, so both follow from the world tile.
 */
final class LocationTool extends DevTool<MapCoord> {
    private final ToolOption tile = option("tile", "World tile", true);
    private final ToolOption mapSquare = option("mapSquare", "Map square", true);
    private final ToolOption mapSquareTile = option("mapSquareTile", "Tile in map square", true);
    private final ToolOption zone = option("zone", "Zone", false);

    LocationTool() {
        super("location", "Location", Category.MAP, MaterialDesignM.MAP_MARKER_OUTLINE,
                "Your world tile, the map square (64×64 region) and zone (8×8) it's in, and your tile within the map square.");
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
    protected MapCoord read(Client client) {
        return localTile(client);
    }

    @Override
    protected void paintOverlay(MapCoord at, OverlayContext overlay) {
        overlay.infoPanel("Location", facts(at, true));
    }

    @Override
    protected ToolView<MapCoord> createView() {
        return new FactsView<>(at -> facts(at, false));
    }

    /** The rows to show; {@code onlyEnabled} applies the options (the window always shows everything). */
    private Map<String, String> facts(MapCoord at, boolean onlyEnabled) {
        int squareX = at.x() >> 6;
        int squareZ = at.z() >> 6;
        Map<String, String> rows = new LinkedHashMap<>();
        if (!onlyEnabled || tile.isOn()) {
            rows.put("Tile", at.x() + ", " + at.z() + ", " + at.level());
        }
        if (!onlyEnabled || mapSquare.isOn()) {
            rows.put("Map square", squareX + ", " + squareZ + " (" + (squareX << 8 | squareZ) + ")");
        }
        if (!onlyEnabled || mapSquareTile.isOn()) {
            rows.put("In square", (at.x() & 63) + ", " + (at.z() & 63));
        }
        if (!onlyEnabled || zone.isOn()) {
            rows.put("Zone", (at.x() >> 3) + ", " + (at.z() >> 3));
        }
        return rows;
    }
}
