package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.actor.Player;
import com.kraken.api.collision.CollisionData;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.Ikon;

import java.awt.*;

/**
 * Highlights the tiles around you that pass a collision check from your tile: {@code lineOfWalk} for "Valid movement"
 * (the tiles next to you) and {@code lineOfSight} for "Line of sight" (tiles within {@link TileFlagsTool#RADIUS}).
 */
final class MovementTool extends DevTool<Marks> {
    private static final Color WALK = new Color(118, 176, 65);
    private static final Color WALK_FILL = new Color(118, 176, 65, 50);
    private static final Color SIGHT = new Color(90, 200, 240);
    private static final Color SIGHT_FILL = new Color(90, 200, 240, 40);

    private final boolean sight;

    private MovementTool(String id, String name, Ikon icon, String description, boolean sight) {
        super(id, name, Category.MAP, icon, description);
        this.sight = sight;
    }

    /**
     * The tiles next to you that you can step onto.
     *
     * @param icon the sidebar icon
     * @return the tool
     */
    static MovementTool walk(Ikon icon) {
        return new MovementTool("validMovement", "Valid movement", icon,
                "Highlights the tiles next to you that you can walk to (lineOfWalk).", false);
    }

    /**
     * The tiles you can see from where you stand.
     *
     * @param icon the sidebar icon
     * @return the tool
     */
    static MovementTool sight(Ikon icon) {
        return new MovementTool("lineOfSight", "Line of sight", icon,
                "Highlights the tiles within " + TileFlagsTool.RADIUS + " that you have line of sight to (lineOfSight).",
                true);
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
        WorldPoint world = self == null ? null : self.getWorldLocation();
        Projector projector = local == null ? null : Projector.of(session);
        CollisionData map = projector == null ? null : TileFlagsTool.collisionMap(client, world.getPlane());
        if (map == null) {
            return null;
        }
        int plane = world.getPlane();
        int sx = local.getSceneX();
        int sy = local.getSceneY();
        int radius = sight ? TileFlagsTool.RADIUS : 1;
        Marks.Builder marks = new Marks.Builder(projector);
        for (int x = sx - radius; x <= sx + radius; x++) {
            for (int y = sy - radius; y <= sy + radius; y++) {
                if (x == sx && y == sy) {
                    continue;
                }
                boolean ok = sight ? map.lineOfSight(sx, sy, x, y) : map.lineOfWalk(sx, sy, x, y);
                if (ok) {
                    marks.outline(projector.tile(plane, x, y), sight ? SIGHT : WALK, sight ? SIGHT_FILL : WALK_FILL);
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
