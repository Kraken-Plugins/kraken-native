package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.CollisionFlag;
import com.kraken.api.lua.CollisionMap;
import com.kraken.api.lua.MapCoord;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.awt.*;

/**
 * Collision flags on the tiles around you ({@code World.getCollisionMap}): blocked tiles filled, walls drawn on the
 * edges they block, and optionally each tile's flags in hex.
 */
final class TileFlagsTool extends DevTool<Marks> {
    static final int RADIUS = 10;
    private static final Color BLOCKED = new Color(230, 80, 80);
    private static final Color BLOCKED_FILL = new Color(230, 80, 80, 60);
    private static final Color WALL = new Color(0xf0c850);
    private static final Color VALUE = new Color(0xdcdcdc);
    private static final int BLOCKS_TILE = CollisionFlag.WALK_SCENARY | CollisionFlag.WR_GROUND_DECOR
            | CollisionFlag.WR_GRND | CollisionFlag.UNLOADED;
    private static final int[] EDGE_FLAGS = {CollisionFlag.W_S, CollisionFlag.W_E, CollisionFlag.W_N, CollisionFlag.W_W};

    private final ToolOption values = option("values", "Flag values", false);

    TileFlagsTool() {
        super("tileFlags", "Tile flags", Category.MAP, MaterialDesignF.FLAG_OUTLINE,
                "Collision flags within " + RADIUS + " tiles: blocked tiles, and walls on the edges they block.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    protected Marks read(KrakenApi api) {
        MapCoord self = api.lua().clientOp().coord();
        Projector projector = self == null ? null : Projector.of(api);
        CollisionMap map = projector == null ? null : api.lua().world().getCollisionMap(self.level());
        if (map == null) {
            return null;
        }
        Marks.Builder marks = new Marks.Builder(projector);
        for (int x = self.x() - RADIUS; x <= self.x() + RADIUS; x++) {
            for (int z = self.z() - RADIUS; z <= self.z() + RADIUS; z++) {
                int flags = map.getBitFlags(x, z);
                if (flags == 0) {
                    continue;
                }
                MapCoord tile = new MapCoord(self.level(), x, z);
                if ((flags & BLOCKS_TILE) != 0) {
                    marks.outline(projector.tile(tile), BLOCKED, BLOCKED_FILL);
                }
                for (int side = 0; side < 4; side++) {
                    if ((flags & EDGE_FLAGS[side]) != 0) {
                        marks.line(projector.edge(tile, side), WALL);
                    }
                }
                if (values.isOn()) {
                    marks.label(Integer.toHexString(flags), projector.tileCenter(tile, 0), VALUE);
                }
            }
        }
        return marks.build();
    }

    @Override
    protected void paintOverlay(Marks marks, OverlayContext overlay) {
        marks.paint(overlay);
    }
}
