package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.actor.Actor;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.Projectile;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projectiles in flight (arrows, spells, cannonballs...): a live table of each one's id, source, target, timing and
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
     * @param x         its local x, or null before it sets off
     * @param y         its local y (north), or null before it sets off
     * @param height    its height, or null before it sets off
     * @param start     the cycle it sets off on
     * @param end       the cycle it lands on
     * @param remaining cycles until it lands
     * @param state     "flying", "waiting" (not set off yet) or "hidden"
     * @param yaw       its heading (0-2047), or null before it sets off
     */
    record Row(String key, int id, String source, String target, Integer x, Integer y, Integer height, int start,
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
        Client client = api.client();
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        Map<String, Integer> keys = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Projectile p : client.getProjectiles()) {
            boolean placed = p.isStarted();
            String identity = p.getId() + ":" + p.getStartCycle() + ":" + p.getEndCycle() + ":" + p.getSourcePoint()
                    + ":" + p.getTargetPoint();
            String key = identity + "#" + keys.merge(identity, 1, Integer::sum);
            String state = p.isHidden() ? "hidden" : placed ? "flying" : "waiting";
            rows.add(new Row(key, p.getId(), endpoint(p.getSourceActor(), p.getSourcePoint()),
                    endpoint(p.getTargetActor(), p.getTargetPoint()),
                    placed ? (int) Math.round(p.getX()) : null, placed ? (int) Math.round(p.getY()) : null,
                    placed ? (int) Math.round(p.getZ()) : null, p.getStartCycle(), p.getEndCycle(),
                    p.getRemainingCycles(), state, placed ? p.getOrientation() : null));
            if (marks != null && placed && !p.isHidden()) {
                mark(marks, projector, p);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, Projectile p) {
        Point point = projector.at((int) Math.round(p.getX()), (int) Math.round(p.getY()),
                (int) Math.round(p.getZ()));
        if (point == null) {
            return;
        }
        marks.outline(new Point[]{new Point(point.getX(), point.getY() - MARKER),
                new Point(point.getX() + MARKER, point.getY()), new Point(point.getX(), point.getY() + MARKER),
                new Point(point.getX() - MARKER, point.getY())}, COLOR, FILL);
        if (paths.isOn()) {
            Point aim = aim(projector, p);
            if (aim != null) {
                marks.line(new Point[]{point, aim}, PATH);
            }
        }
        if (ids.isOn()) {
            marks.label(String.valueOf(p.getId()), new Point(point.getX(), point.getY() - LABEL_GAP), COLOR);
        }
    }

    /**
     * Where a projectile is heading, the way the client aims it: the target actor's position while it can be found,
     * else the target tile's centre, raised by the endpoint's height.
     *
     * @param projector this frame's projection
     * @param p         the projectile
     * @return the screen point, or null if it can't be projected
     */
    private static Point aim(Projector projector, Projectile p) {
        Actor actor = p.getTargetActor();
        WorldPoint tile = p.getTargetPoint();
        if (actor != null && actor.getLocalLocation() != null && tile != null) {
            return projector.above(tile.getPlane(), actor.getLocalLocation(), p.getEndHeight());
        }
        if (tile == null) {
            return null;
        }
        LocalPoint centre = LocalPoint.fromWorld(projector.view(), tile);
        return centre == null ? null : projector.above(tile.getPlane(), centre, p.getEndHeight());
    }

    /**
     * A label for one end of a projectile's path.
     *
     * @param actor the actor at that end, or null
     * @param tile  the world tile at that end, or null
     * @return e.g. "NPC 1234 Goblin", or "Tile (3202, 3203, 0)" (a world tile) when it isn't an actor
     */
    private static String endpoint(Actor actor, WorldPoint tile) {
        if (actor != null) {
            return Actors.label(actor);
        }
        return tile == null ? "" : "Tile (" + tile.getX() + ", " + tile.getY() + ", " + tile.getPlane() + ")";
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
                .column("Y", Integer.class, 56, Row::y)
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
