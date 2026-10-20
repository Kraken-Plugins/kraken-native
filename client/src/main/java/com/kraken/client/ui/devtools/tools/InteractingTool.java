package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.Entity;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Npc;
import com.kraken.api.lua.Player;
import com.kraken.api.lua.Vector2;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is interacting with whom: every NPC and player with a target ({@code isInteracting}), and what it is, in a table
 * and as lines between them over the game.
 */
final class InteractingTool extends DevTool<Listing<InteractingTool.Row>> {
    private static final Color LINE = new Color(240, 120, 200);
    private static final int LIFT = 100;

    record Row(String key, String source, String target, Integer distance) {
    }

    InteractingTool() {
        super("interacting", "Interacting", Category.ACTORS, MaterialDesignT.TARGET_ACCOUNT,
                "Every NPC and player that is interacting with something, and its target (isInteracting).");
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
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        List<Row> rows = new ArrayList<>();
        for (Player player : client.getPlayers()) {
            add(rows, marks, projector, client, "p" + player.index(), "Player " + player.index() + " " + player.getName(),
                    player, client.playerCoordFine(player.index()));
        }
        for (Npc npc : client.getNpcs()) {
            add(rows, marks, projector, client, "n" + npc.index(), "NPC " + npc.index() + " " + npc.getName(), npc,
                    client.npcCoordFine(npc.index()));
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private static void add(List<Row> rows, Marks.Builder marks, Projector projector, ClientOp client, String key,
                            String source, Entity entity, FineCoord from) {
        int type = entity.getInteractingType();
        int id = entity.isInteracting();
        if (id < 0) {
            return;
        }
        MapCoord at = entity.getCoord();
        MapCoord to = switch (type) {
            case Entity.TARGET_NPC -> client.npcCoord(id);
            case Entity.TARGET_PLAYER -> client.playerCoord(id);
            default -> null;
        };
        rows.add(new Row(key, source, Actors.target(client, type, id), NpcsTool.distance(at, to)));
        if (marks != null) {
            FineCoord target = switch (type) {
                case Entity.TARGET_NPC -> client.npcCoordFine(id);
                case Entity.TARGET_PLAYER -> client.playerCoordFine(id);
                default -> null;
            };
            Vector2 a = projector.above(from, LIFT);
            Vector2 b = projector.above(target, LIFT);
            if (a != null && b != null) {
                marks.line(new Vector2[]{a, b}, LINE);
            }
        }
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("interactions")
                .column("Actor", String.class, 220, Row::source)
                .column("Interacting with", String.class, 220, Row::target)
                .column("Dist", Integer.class, 50, Row::distance)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
