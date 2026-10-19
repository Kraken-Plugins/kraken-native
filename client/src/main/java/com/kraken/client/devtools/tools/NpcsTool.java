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

/**
 * Every NPC loaded around you, in a live table. Labels above their heads come once the API can project.
 */
final class NpcsTool extends DevTool<List<NpcsTool.Row>> {
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
            MapCoord at = npc.getCoord();
            String follower = npc.isLocalFollower() ? "yours" : npc.isFollower() ? "yes" : "";
            rows.add(new Row(npc.index(), npc.getTypeId(), npc.getName(),
                    at == null ? null : at.x(), at == null ? null : at.z(), at == null ? null : at.level(),
                    distance(self, at), Actors.height(client.npcCoordFine(npc.index())), npc.getType(),
                    Actors.target(client, npc), Actors.health(npc), follower, Actors.hidden(npc), npc.getUid(),
                    npc.getCreationCycle()));
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
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
