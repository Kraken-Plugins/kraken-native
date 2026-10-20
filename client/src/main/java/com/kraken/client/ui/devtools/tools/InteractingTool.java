package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
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

    /**
     * One interaction's row.
     *
     * @param key      a key unique among the rows
     * @param source   the interacting actor, e.g. "NPC 12 Goblin"
     * @param target   what it targets, e.g. "Player 3 Name"
     * @param distance the tile distance between them, or null
     */
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
        Client client = api.client();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        List<Row> rows = new ArrayList<>();
        for (Player player : client.getPlayers()) {
            add(rows, marks, projector, "p" + player.getId(),
                    "Player " + player.getId() + " " + player.getName(), player);
        }
        for (NPC npc : client.getNpcs()) {
            add(rows, marks, projector, "n" + npc.getIndex(), "NPC " + npc.getIndex() + " " + npc.getName(), npc);
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private static void add(List<Row> rows, Marks.Builder marks, Projector projector, String key, String source,
                            Actor actor) {
        if (!actor.isInteracting()) {
            return;
        }
        Actor target = actor.getInteracting();
        WorldPoint at = actor.getWorldLocation();
        WorldPoint to = target == null ? null : target.getWorldLocation();
        rows.add(new Row(key, source, Actors.target(actor), NpcsTool.distance(at, to)));
        if (marks != null && at != null) {
            Point a = projector.above(at.getPlane(), actor.getLocalLocation(), LIFT);
            Point b = target == null || to == null ? null
                    : projector.above(to.getPlane(), target.getLocalLocation(), LIFT);
            marks.line(a == null || b == null ? null : new Point[]{a, b}, LINE);
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
