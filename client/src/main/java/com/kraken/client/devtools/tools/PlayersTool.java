package com.kraken.client.devtools.tools;

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

/** Every player loaded around you, including yourself, in a live table. */
final class PlayersTool extends DevTool<List<PlayersTool.Row>> {
    record Row(int index, String name, boolean self, int x, int z, int level, Integer distance) {
    }

    PlayersTool() {
        super("players", "Players", Category.ACTORS, MaterialDesignA.ACCOUNT_OUTLINE,
                "Every player loaded around you, including you, with their index, name and tile.");
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
    protected List<Row> read(Client client) {
        int selfIndex = client.getLocalPlayerIndex();
        MapCoord self = localTile(client);
        List<Row> rows = new ArrayList<>();
        for (Player player : client.getPlayers()) {
            MapCoord at = player.location();
            boolean isSelf = player.index() == selfIndex;
            rows.add(at == null
                    ? new Row(player.index(), player.name(), isSelf, 0, 0, 0, null)
                    : new Row(player.index(), player.name(), isSelf, at.x(), at.z(), at.level(), NpcsTool.distance(self, at)));
        }
        return rows;
    }

    @Override
    protected ToolView<List<Row>> createView() {
        return TableView.<Row>builder("Players")
                .column("Index", Integer.class, 56, Row::index)
                .column("Name", String.class, 180, Row::name)
                .column("You", Boolean.class, 40, Row::self)
                .column("X", Integer.class, 60, Row::x)
                .column("Z", Integer.class, 60, Row::z)
                .column("Plane", Integer.class, 50, Row::level)
                .column("Dist", Integer.class, 50, Row::distance)
                .key(Row::index)
                .build();
    }
}
