package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Client;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Npc;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;

import java.util.ArrayList;
import java.util.List;

/** Every NPC loaded around you, in a live table. Labels above their heads come once the API can project. */
final class NpcsTool extends DevTool<List<NpcsTool.Row>> {
    record Row(int index, int id, String name, int x, int z, int level, Integer distance) {
    }

    NpcsTool() {
        super("npcs", "NPCs", Category.ACTORS, MaterialDesignG.GHOST_OUTLINE,
                "Every NPC loaded around you with its index, id, name and tile.");
    }

    @Override
    public Support overlaySupport() {
        return Support.PLANNED;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    public String needs() {
        return "world-to-screen projection, for labels above heads";
    }

    @Override
    protected List<Row> read(KrakenApi api) {
        Client client = api.client();
        MapCoord self = localTile(client);
        List<Row> rows = new ArrayList<>();
        for (Npc npc : client.getNpcs()) {
            MapCoord at = npc.location();
            rows.add(at == null
                    ? new Row(npc.index(), npc.typeId(), npc.name(), 0, 0, 0, null)
                    : new Row(npc.index(), npc.typeId(), npc.name(), at.x(), at.z(), at.level(), distance(self, at)));
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("NPCs")
                .column("Index", Integer.class, 56, Row::index)
                .column("Id", Integer.class, 60, Row::id)
                .column("Name", String.class, 180, Row::name)
                .column("X", Integer.class, 60, Row::x)
                .column("Z", Integer.class, 60, Row::z)
                .column("Plane", Integer.class, 50, Row::level)
                .column("Dist", Integer.class, 50, Row::distance)
                .key(Row::index)
                .build();
    }

    /** Tile distance from you, or null if either tile is unknown or on another plane. */
    static Integer distance(MapCoord self, MapCoord at) {
        return self == null || at == null || self.level() != at.level() ? null : self.distanceTo(at);
    }
}
