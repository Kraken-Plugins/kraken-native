package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.MapCoord;
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
 * Where you're standing, as an info box over the game and in a window. The client keeps tiles relative to the loaded
 * map's base (the scene tile); adding the base gives the world tile, from which the map square (64×64 region) and zone
 * (8×8) follow.
 *
 * <p>The RuneLite view shows the same position through the RuneLite-shaped API ({@code KrakenApi.client()}), the local
 * player's {@code WorldPoint} and {@code LocalPoint}, and flags a world tile that disagrees with the Lua one.
 */
final class LocationTool extends DevTool<LocationTool.Where> {

    /**
     * One read of where the local player is.
     *
     * @param scene   the scene tile, from the Lua mirror
     * @param world   the world tile (scene tile plus base), or null if the base can't be read
     * @param base    the map base, or null if it can't be read
     * @param rlWorld the RuneLite-shaped world tile, or null if it can't be read
     * @param rlLocal the RuneLite-shaped local point, or null if it can't be read
     */
    record Where(MapCoord scene, MapCoord world, MapCoord base, WorldPoint rlWorld, LocalPoint rlLocal) {
    }

    private final ToolOption tile = option("tile", "World tile", true);
    private final ToolOption sceneTile = option("sceneTile", "Scene tile", false);
    private final ToolOption mapSquare = option("mapSquare", "Map square", true);
    private final ToolOption mapSquareTile = option("mapSquareTile", "Tile in map square", true);
    private final ToolOption zone = option("zone", "Zone", false);
    private final ToolOption runelite = option("runelite", "RuneLite view", false);

    LocationTool() {
        super("location", "Location", Category.MAP, MaterialDesignM.MAP_MARKER_OUTLINE,
                "Your world tile, your tile in the loaded scene, the map square (64×64 region) and zone (8×8) you're in, "
                        + "and your tile within the map square. The RuneLite view adds the WorldPoint and LocalPoint "
                        + "the RuneLite-shaped API reads.");
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
        ClientOp client = api.lua().clientOp();
        MapCoord at = localTile(client);
        if (at == null) {
            return null;
        }
        MapCoord base = api.lua().scene().getBase();
        Player self = api.client().getLocalPlayer();
        WorldPoint rlWorld = self == null ? null : self.getWorldLocation();
        LocalPoint rlLocal = self == null ? null : self.getLocalLocation();
        return new Where(at, base == null ? null : api.lua().scene().toWorld(at), base, rlWorld, rlLocal);
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
        if (!onlyEnabled || runelite.isOn()) {
            runeLiteRows(where, rows);
        }
        return rows;
    }

    /**
     * Adds the RuneLite view: the local player's {@code WorldPoint}, region and {@code LocalPoint}.
     *
     * @param where where you are
     * @param rows  the rows to add to
     */
    private static void runeLiteRows(Where where, Map<String, String> rows) {
        WorldPoint rl = where.rlWorld();
        MapCoord world = where.world();
        if (rl == null) {
            rows.put("WorldPoint", "?");
        } else {
            boolean matches = world != null && rl.getX() == world.x() && rl.getY() == world.z()
                    && rl.getPlane() == world.level();
            rows.put("WorldPoint", rl.getX() + ", " + rl.getY() + ", " + rl.getPlane() + (matches ? "" : " (≠ Lua)"));
            rows.put("Region ID", String.valueOf(rl.getRegionID()));
        }
        LocalPoint local = where.rlLocal();
        rows.put("LocalPoint", local == null ? "?"
                : local.getX() + ", " + local.getY() + " (scene " + local.getSceneX() + ", " + local.getSceneY() + ")");
    }
}
