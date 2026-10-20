package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Player;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Every player loaded around you, including yourself, in a live table, and over the game their names above their
 * heads and the tiles they stand on.
 */
final class PlayersTool extends DevTool<Listing<PlayersTool.Row>> {
    private static final Color COLOR = new Color(0x5ac8f0);
    private static final Color FILL = new Color(90, 200, 240, 30);
    private static final Color SELF_FILL = new Color(118, 176, 65, 30);

    private final ToolOption names = option("names", "Names", true);
    private final ToolOption tiles = option("tiles", "Tiles", true);
    private final ToolOption self = option("self", "Include yourself", true);

    record Row(int index, String name, boolean self, Integer x, Integer z, Integer level, Integer distance,
               Integer height, int route, String target, String health, String relation, String hidden) {
    }

    PlayersTool() {
        super("players", "Players", Category.ACTORS, MaterialDesignA.ACCOUNT_OUTLINE,
                "Every player loaded around you, including you: index, name, tile, ground height, queued steps, "
                        + "interaction target, health bar, and whether they're a friend, in your chat or clan, or ignored.");
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
        for (Player player : client.getPlayers()) {
            MapCoord at = player.getCoord();
            int index = player.index();
            if (marks != null) {
                mark(marks, projector, client, player, at);
            }
            rows.add(new Row(index, player.getName(), player.isLocalPlayer(),
                    at == null ? null : at.x(), at == null ? null : at.z(), at == null ? null : at.level(),
                    NpcsTool.distance(self, at), Actors.height(client.playerCoordFine(index)),
                    client.playerRouteLength(index), Actors.target(client, player), Actors.health(player),
                    relation(player), Actors.hidden(player)));
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, ClientOp client, Player player, MapCoord at) {
        boolean local = player.isLocalPlayer();
        if (at == null || (local && !self.isOn())) {
            return;
        }
        Color color = local ? Theme.ACCENT : COLOR;
        if (tiles.isOn()) {
            marks.outline(projector.tile(at), color, local ? SELF_FILL : FILL);
        }
        if (names.isOn()) {
            marks.label(player.getName(), projector.above(client.playerCoordFine(player.index()), Projector.LABEL_LIFT),
                    color);
        }
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
        return TableView.<Row>builder("Players")
                .column("Index", Integer.class, 56, Row::index)
                .column("Name", String.class, 150, Row::name)
                .column("You", Boolean.class, 40, Row::self)
                .column("X", Integer.class, 56, Row::x)
                .column("Z", Integer.class, 56, Row::z)
                .column("Plane", Integer.class, 46, Row::level)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Height", Integer.class, 56, Row::height)
                .column("Steps", Integer.class, 46, Row::route)
                .column("Interacting", String.class, 160, Row::target)
                .column("Health", String.class, 80, Row::health)
                .column("Relation", String.class, 120, Row::relation)
                .column("Hidden", String.class, 64, Row::hidden)
                .key(Row::index)
                .build();
    }

    /**
     * "friend", "chat", "clan", "ignored", joined; "" for none.
     *
     * @param player the player
     * @return the relations that hold, comma-separated
     */
    private static String relation(Player player) {
        StringJoiner relation = new StringJoiner(", ");
        if (player.isOnlineFriend()) {
            relation.add("friend");
        }
        if (player.isFriendChat()) {
            relation.add("chat");
        }
        if (player.isGroup()) {
            relation.add("clan");
        }
        if (player.isIgnored()) {
            relation.add("ignored");
        }
        return relation.toString();
    }
}
