package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.Entity;
import com.kraken.api.lua.FineCoord;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Projectile;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.Vector2;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.MappedView;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projectiles in flight (arrows, spells, cannonballs…): a live table of each one's id, source, target, timing and
 * position, and over the game a marker on each with its id and a line to where it is heading.
 */
final class ProjectilesTool extends DevTool<Listing<ProjectilesTool.Row>> {
    private static final Color COLOR = new Color(0x60d0f0);
    private static final Color FILL = new Color(96, 208, 240, 90);
    private static final Color PATH = new Color(96, 208, 240, 150);

    /**
     * Half the marker's width, and how far above the marker the id goes, in client pixels.
     */
    private static final int MARKER = 5;
    private static final int LABEL_GAP = 12;

    private final ToolOption ids = option("ids", "Ids", true);
    private final ToolOption paths = option("paths", "Paths", true);

    /**
     * One table row.
     *
     * @param key       identifies the projectile across polls
     * @param id        its spot anim id
     * @param source    where it comes from, e.g. "Player 7 Zezima" or "Tile (3202, 3203, 0)"
     * @param target    where it is going
     * @param x         its fine x, or null before it sets off
     * @param z         its fine z, or null before it sets off
     * @param height    its height, or null before it sets off
     * @param start     the cycle it sets off on
     * @param end       the cycle it lands on
     * @param remaining cycles until it lands
     * @param state     "flying", "waiting" (not set off yet) or "hidden"
     * @param yaw       its heading (0-2047), or null before it sets off
     */
    record Row(String key, int id, String source, String target, Integer x, Integer z, Integer height, int start,
               int end, int remaining, String state, Integer yaw) {
    }

    ProjectilesTool() {
        super("projectiles", "Projectiles", Category.ACTORS, MaterialDesignB.BULLSEYE_ARROW,
                "Projectiles in flight: spot anim id, source and target, start and end cycles, cycles left, position "
                        + "and heading.");
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
        Scene scene = api.lua().scene();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        Map<String, Integer> keys = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Projectile p : client.getProjectiles()) {
            FineCoord at = p.position();
            boolean placed = !at.isMissing();
            String identity = p.id() + ":" + p.startCycle() + ":" + p.endCycle() + ":" + p.source().tile() + ":"
                    + p.target().tile();
            String key = identity + "#" + keys.merge(identity, 1, Integer::sum);
            String state = !p.isRendered() ? "hidden" : placed ? "flying" : "waiting";
            rows.add(new Row(key, p.id(), endpoint(client, p.source()), endpoint(client, p.target()),
                    placed ? at.x() : null, placed ? at.z() : null, placed ? at.y() : null, p.startCycle(),
                    p.endCycle(), p.remainingCycles(), state, placed ? p.yaw() : null));
            if (marks != null && placed) {
                mark(marks, projector, client, scene, p, at);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, ClientOp client, Scene scene, Projectile p,
                      FineCoord at) {
        Vector2 point = projector.point(at);
        if (point == null) {
            return;
        }
        marks.outline(new Vector2[]{new Vector2(point.x(), point.y() - MARKER), new Vector2(point.x() + MARKER, point.y()),
                new Vector2(point.x(), point.y() + MARKER), new Vector2(point.x() - MARKER, point.y())}, COLOR, FILL);
        if (paths.isOn()) {
            Vector2 aim = aim(projector, client, scene, p.target());
            if (aim != null) {
                marks.line(new Vector2[]{point, aim}, PATH);
            }
        }
        if (ids.isOn()) {
            marks.label(String.valueOf(p.id()), new Vector2(point.x(), point.y() - LABEL_GAP), COLOR);
        }
    }

    /**
     * Where a projectile is heading, the way the client aims it: the target actor's position while it can be found,
     * else the target tile's centre, raised by the endpoint's height.
     *
     * @param projector this frame's projection
     * @param client    finds the target actor
     * @param scene     turns the target's world tile into a scene tile
     * @param end       the projectile's target
     * @return the screen point, or null if it can't be projected
     */
    private static Vector2 aim(Projector projector, ClientOp client, Scene scene, Projectile.Endpoint end) {
        FineCoord actor = switch (end.actorType()) {
            case Entity.TARGET_NPC -> client.npcCoordFine(end.actorIndex());
            case Entity.TARGET_PLAYER -> client.playerCoordFine(end.actorIndex());
            default -> null;
        };
        if (actor != null && !actor.isMissing()) {
            return projector.above(actor, end.height());
        }
        MapCoord tile = scene.toScene(end.tile());
        return tile == null ? null : projector.tileCenter(tile, end.height());
    }

    /**
     * A label for one end of a projectile's path.
     *
     * @param client reads the actor's name
     * @param end    the endpoint
     * @return e.g. "NPC 1234 Goblin", or "Tile (3202, 3203, 0)" (a world tile) when it isn't an actor
     */
    private static String endpoint(ClientOp client, Projectile.Endpoint end) {
        return end.isActor() ? Actors.target(client, end.actorType(), end.actorIndex()) : "Tile " + end.tile();
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("projectiles")
                .column("Id", Integer.class, 56, Row::id)
                .column("From", String.class, 170, Row::source)
                .column("To", String.class, 170, Row::target)
                .column("X", Integer.class, 56, Row::x)
                .column("Z", Integer.class, 56, Row::z)
                .column("Height", Integer.class, 56, Row::height)
                .column("Start", Integer.class, 64, Row::start)
                .column("End", Integer.class, 64, Row::end)
                .column("Left", Integer.class, 50, Row::remaining)
                .column("State", String.class, 64, Row::state)
                .column("Yaw", Integer.class, 50, Row::yaw)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
