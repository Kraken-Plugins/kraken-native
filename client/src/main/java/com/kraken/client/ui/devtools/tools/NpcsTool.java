package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Npc;
import com.kraken.api.lua.types.NpcType;
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

    record Row(int index, int id, String name, Integer x, Integer z, Integer level, Integer distance, Integer height,
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
        ClientOp client = api.lua().clientOp();
        MapCoord self = localTile(client);
        List<Row> rows = new ArrayList<>();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        for (Npc npc : client.getNpcs()) {
            if (marks != null) {
                mark(marks, projector, client, npc);
            }
            MapCoord at = npc.getCoord();
            String follower = npc.isLocalFollower() ? "yours" : npc.isFollower() ? "yes" : "";
            rows.add(new Row(npc.index(), npc.getTypeId(), npc.getName(),
                    at == null ? null : at.x(), at == null ? null : at.z(), at == null ? null : at.level(),
                    distance(self, at), Actors.height(client.npcCoordFine(npc.index())), npc.getType(),
                    Actors.target(client, npc), Actors.health(npc), follower, Actors.hidden(npc), npc.getUid(),
                    npc.getCreationCycle()));
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, ClientOp client, Npc npc) {
        MapCoord at = npc.getCoord();
        if (at == null) {
            return;
        }
        if (tiles.isOn()) {
            NpcType type = npc.getTypeData();
            int size = type == null ? 1 : Math.max(1, type.getSize());
            marks.outline(projector.area(at, size, size), COLOR, FILL);
        }
        String label = names.isOn() ? npc.getName() : "";
        if (ids.isOn()) {
            label = (label.isEmpty() ? "" : label + " ") + "(" + npc.getTypeId() + ")";
        }
        FineCoord fine = client.npcCoordFine(npc.index());
        marks.label(label, projector.above(fine, Projector.LABEL_LIFT), COLOR);
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
                .column("Z", Integer.class, 56, Row::z)
                .column("Plane", Integer.class, 46, Row::level)
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
    static Integer distance(MapCoord self, MapCoord at) {
        return self == null || at == null || self.level() != at.level() ? null : self.distanceTo(at);
    }
}
