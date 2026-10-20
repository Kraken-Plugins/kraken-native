package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.Point;
import com.kraken.api.actor.Player;
import com.kraken.api.collision.CollisionData;
import com.kraken.api.collision.CollisionDataFlag;
import com.kraken.api.coords.LocalPoint;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.awt.*;

/**
 * Collision flags on the tiles around you ({@link com.kraken.api.scene.WorldView#getCollisionMaps}): blocked tiles
 * filled, walls drawn on the edges they block, and optionally each tile's flags in hex.
 */
final class TileFlagsTool extends DevTool<Marks> {
    static final int RADIUS = 10;
    private static final Color BLOCKED = new Color(230, 80, 80);
    private static final Color BLOCKED_FILL = new Color(230, 80, 80, 60);
    private static final Color WALL = new Color(0xf0c850);
    private static final Color VALUE = new Color(0xdcdcdc);
    private static final int BLOCKS_TILE = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
            | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
            | CollisionDataFlag.UNLOADED;
    /**
     * The edge flags by side: south, east, north, west.
     */
    private static final int[] EDGE_FLAGS = {CollisionDataFlag.BLOCK_MOVEMENT_SOUTH,
            CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_NORTH,
            CollisionDataFlag.BLOCK_MOVEMENT_WEST};

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
    protected Marks read(GameSession session) {
        Client client = session.client();
        Player self = client.getLocalPlayer();
        LocalPoint local = self == null ? null : self.getLocalLocation();
        Projector projector = local == null ? null : Projector.of(session);
        int plane = self == null || self.getWorldLocation() == null ? 0 : self.getWorldLocation().getPlane();
        CollisionData map = projector == null ? null : collisionMap(client, plane);
        if (map == null) {
            return null;
        }
        int sx = local.getSceneX();
        int sy = local.getSceneY();
        Marks.Builder marks = new Marks.Builder(projector);
        for (int x = sx - RADIUS; x <= sx + RADIUS; x++) {
            for (int y = sy - RADIUS; y <= sy + RADIUS; y++) {
                int flags = map.getBitFlags(x, y);
                if (flags == 0) {
                    continue;
                }
                if ((flags & BLOCKS_TILE) != 0) {
                    marks.outline(projector.tile(plane, x, y), BLOCKED, BLOCKED_FILL);
                }
                for (int side = 0; side < 4; side++) {
                    if ((flags & EDGE_FLAGS[side]) != 0) {
                        marks.line(projector.edge(plane, x, y, side), WALL);
                    }
                }
                if (values.isOn()) {
                    Point centre = projector.tileCenter(plane, x, y, 0);
                    marks.label(Integer.toHexString(flags), centre, VALUE);
                }
            }
        }
        return marks.build();
    }

    /**
     * One plane's collision flags, copied from the top-level scene now.
     *
     * @param client the client
     * @param plane  the plane
     * @return the flags, or null if they can't be read
     */
    static CollisionData collisionMap(Client client, int plane) {
        CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
        return maps == null || plane < 0 || plane >= maps.length ? null : maps[plane];
    }

    @Override
    protected void paintOverlay(Marks marks, OverlayContext overlay) {
        marks.paint(overlay);
    }
}
