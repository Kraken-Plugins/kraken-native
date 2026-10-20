package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.actor.NPC;
import com.kraken.api.coords.WorldPoint;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Every NPC loaded around you, in a live table, and over the game their names above their heads and the tiles they
 * stand on.
 */
final class NpcsTool extends DevTool<Listing<NpcsTool.Row>> {
    private static final Color COLOR = new Color(0xf0c850);
    private static final Color FILL = new Color(240, 200, 80, 30);

    private final ToolOption names = option("names", "Names", true);
    private final ToolOption ids = option("ids", "Ids", false);
    private final ToolOption tiles = option("tiles", "Tiles", true);

    /**
     * One NPC's row. The tile is a world tile.
     *
     * @param index    the NPC's index
     * @param id       the NPC's type id
     * @param name     the NPC's name
     * @param x        the world x, or null
     * @param y        the world y, or null
     * @param plane    the plane, or null
     * @param distance the tile distance from you, or null
     * @param height   the ground height under it, or null
     * @param category the NPC's category
     * @param target   what it's interacting with
     * @param health   "dead", "health bar" or ""
     * @param follower "yours", "yes" or ""
     * @param hidden   what is hidden
     * @param uid      the NPC's uid
     * @param spawned  the cycle it was created on
     */
    record Row(int index, int id, String name, Integer x, Integer y, Integer plane, Integer distance, Integer height,
               int category, String target, String health, String follower, String hidden, int uid, int spawned) {
    }

    NpcsTool() {
        super("npcs", "NPCs", Category.ACTORS, MaterialDesignG.GHOST_OUTLINE,
                "Every NPC loaded around you: index, id, name, tile, ground height, category, interaction target, "
                        + "health bar, follower, uid and creation cycle.");
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
    protected Listing<Row> read(KrakenApi api) {
        Client client = api.client();
        WorldPoint self = localTile(client);
        List<Row> rows = new ArrayList<>();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        for (NPC npc : client.getNpcs()) {
            if (marks != null) {
                mark(marks, projector, npc);
            }
            WorldPoint at = npc.getWorldLocation();
            String follower = npc.isLocalFollower() ? "yours" : npc.isFollower() ? "yes" : "";
            rows.add(new Row(npc.getIndex(), npc.getId(), npc.getName(),
                    at == null ? null : at.getX(), at == null ? null : at.getY(), at == null ? null : at.getPlane(),
                    distance(self, at), Actors.height(client, npc), npc.getCategory(),
                    Actors.target(npc), Actors.health(npc), follower, Actors.hidden(npc), npc.getUid(),
                    npc.getSpawnCycle()));
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, NPC npc) {
        WorldPoint at = npc.getWorldLocation();
        if (at == null) {
            return;
        }
        if (tiles.isOn()) {
            marks.outline(projector.footprint(at.getPlane(), npc.getLocalLocation(), npc.getFootprintSize()),
                    COLOR, FILL);
        }
        String label = names.isOn() ? npc.getName() : "";
        if (ids.isOn()) {
            label = (label.isEmpty() ? "" : label + " ") + "(" + npc.getId() + ")";
        }
        marks.label(label, projector.above(at.getPlane(), npc.getLocalLocation(), Projector.LABEL_LIFT), COLOR);
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(table(), Listing::rows);
    }

    private static TableView<Row> table() {
        return TableView.<Row>builder("NPCs")
                .column("Index", Integer.class, 56, Row::index)
                .column("Id", Integer.class, 60, Row::id)
                .column("Name", String.class, 160, Row::name)
                .column("X", Integer.class, 56, Row::x)
                .column("Y", Integer.class, 56, Row::y)
                .column("Plane", Integer.class, 46, Row::plane)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Height", Integer.class, 56, Row::height)
                .column("Category", Integer.class, 64, Row::category)
                .column("Interacting", String.class, 160, Row::target)
                .column("Health", String.class, 80, Row::health)
                .column("Follower", String.class, 64, Row::follower)
                .column("Hidden", String.class, 64, Row::hidden)
                .column("Uid", Integer.class, 90, Row::uid)
                .column("Created", Integer.class, 70, Row::spawned)
                .key(Row::index)
                .build();
    }

    /**
     * Tile distance from you, or null if either tile is unknown or on another plane.
     *
     * @param self your tile, or null
     * @param at   the other tile, or null
     * @return the Chebyshev distance in tiles, or null
     */
    static Integer distance(WorldPoint self, WorldPoint at) {
        return self == null || at == null || self.getPlane() != at.getPlane() ? null : self.distanceTo(at);
    }
}
