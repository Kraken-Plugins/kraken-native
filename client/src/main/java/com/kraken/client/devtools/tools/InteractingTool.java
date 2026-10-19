package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Client;
import com.kraken.api.game.Entity;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Npc;
import com.kraken.api.game.Player;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is interacting with whom: every NPC and player with a target ({@code isInteracting}), and what it is. Lines
 * between them over the game come once the API can project.
 */
final class InteractingTool extends DevTool<List<InteractingTool.Row>> {
    record Row(String key, String source, String target, Integer distance) {
    }

    InteractingTool() {
        super("interacting", "Interacting", Category.ACTORS, MaterialDesignT.TARGET_ACCOUNT,
                "Every NPC and player that is interacting with something, and its target (isInteracting).");
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
        return "world-to-screen projection, for lines between actors";
    }

    @Override
    protected List<Row> read(KrakenApi api) {
        Client client = api.client();
        List<Row> rows = new ArrayList<>();
        for (Player player : client.getPlayers()) {
            add(rows, client, "p" + player.index(), "Player " + player.index() + " " + player.getName(), player);
        }
        for (Npc npc : client.getNpcs()) {
            add(rows, client, "n" + npc.index(), "NPC " + npc.index() + " " + npc.getName(), npc);
        }
        return rows;
    }

    private static void add(List<Row> rows, Client client, String key, String source, Entity entity) {
        int type = entity.getInteractingType();
        int id = entity.isInteracting();
        if (id < 0) {
            return;
        }
        MapCoord from = entity.getCoord();
        MapCoord to = switch (type) {
            case Entity.TARGET_NPC -> client.npcCoord(id);
            case Entity.TARGET_PLAYER -> client.playerCoord(id);
            default -> null;
        };
        rows.add(new Row(key, source, Actors.target(client, type, id), NpcsTool.distance(from, to)));
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("interactions")
                .column("Actor", String.class, 220, Row::source)
                .column("Interacting with", String.class, 220, Row::target)
                .column("Dist", Integer.class, 50, Row::distance)
                .key(Row::key)
                .build();
    }
}
