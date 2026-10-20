package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
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

    /**
     * One player's row. The tile is a world tile.
     *
     * @param index    the player's index
     * @param name     the player's name
     * @param self     whether it's you
     * @param x        the world x, or null
     * @param y        the world y, or null
     * @param plane    the plane, or null
     * @param distance the tile distance from you, or null
     * @param height   the ground height under it, or null
     * @param route    the queued steps
     * @param target   what it's interacting with
     * @param health   "dead", "health bar" or ""
     * @param relation the relations that hold
     * @param hidden   what is hidden
     */
    record Row(int index, String name, boolean self, Integer x, Integer y, Integer plane, Integer distance,
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
        Client client = api.client();
        WorldPoint self = localTile(client);
        List<Row> rows = new ArrayList<>();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        Player local = client.getLocalPlayer();
        for (Player player : client.getPlayers()) {
            WorldPoint at = player.getWorldLocation();
            boolean isSelf = player == local;
            if (marks != null) {
                mark(marks, projector, player, isSelf);
            }
            rows.add(new Row(player.getId(), player.getName(), isSelf,
                    at == null ? null : at.getX(), at == null ? null : at.getY(), at == null ? null : at.getPlane(),
                    NpcsTool.distance(self, at), Actors.height(client, player), player.getPathLength(),
                    Actors.target(player), Actors.health(player), relation(player), Actors.hidden(player)));
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, Player player, boolean local) {
        WorldPoint at = player.getWorldLocation();
        if (at == null || (local && !self.isOn())) {
            return;
        }
        Color color = local ? Theme.ACCENT : COLOR;
        if (tiles.isOn()) {
            marks.outline(projector.footprint(at.getPlane(), player.getLocalLocation(), player.getFootprintSize()),
                    color, local ? SELF_FILL : FILL);
        }
        if (names.isOn()) {
            marks.label(player.getName(),
                    projector.above(at.getPlane(), player.getLocalLocation(), Projector.LABEL_LIFT), color);
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
                .column("Y", Integer.class, 56, Row::y)
                .column("Plane", Integer.class, 46, Row::plane)
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
        if (player.isFriendsChatMember()) {
            relation.add("chat");
        }
        if (player.isClanMember()) {
            relation.add("clan");
        }
        if (player.isIgnored()) {
            relation.add("ignored");
        }
        return relation.toString();
    }
}
