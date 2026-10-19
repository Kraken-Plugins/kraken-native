package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.CollisionMap;
import com.kraken.api.game.MapCoord;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.overlay.OverlayContext;
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
    protected Marks read(KrakenApi api) {
        MapCoord self = api.client().coord();
        Projector projector = self == null ? null : Projector.of(api);
        CollisionMap map = projector == null ? null : api.world().getCollisionMap(self.level());
        if (map == null) {
            return null;
        }
        int radius = sight ? TileFlagsTool.RADIUS : 1;
        Marks.Builder marks = new Marks.Builder(projector);
        for (int x = self.x() - radius; x <= self.x() + radius; x++) {
            for (int z = self.z() - radius; z <= self.z() + radius; z++) {
                if (x == self.x() && z == self.z()) {
                    continue;
                }
                boolean ok = sight ? map.lineOfSight(self.x(), self.z(), x, z) : map.lineOfWalk(self.x(), self.z(), x, z);
                if (ok) {
                    marks.outline(projector.tile(new MapCoord(self.level(), x, z)), sight ? SIGHT : WALK,
                            sight ? SIGHT_FILL : WALK_FILL);
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
