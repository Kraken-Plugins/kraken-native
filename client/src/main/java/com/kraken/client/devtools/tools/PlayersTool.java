package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.game.Client;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Player;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Every player loaded around you, including yourself, in a live table.
 */
final class PlayersTool extends DevTool<List<PlayersTool.Row>> {
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
        for (Player player : client.getPlayers()) {
            MapCoord at = player.getCoord();
            int index = player.index();
            rows.add(new Row(index, player.getName(), player.isLocalPlayer(),
                    at == null ? null : at.x(), at == null ? null : at.z(), at == null ? null : at.level(),
                    NpcsTool.distance(self, at), Actors.height(client.playerCoordFine(index)),
                    client.playerRouteLength(index), Actors.target(client, player), Actors.health(player),
                    relation(player), Actors.hidden(player)));
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
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
