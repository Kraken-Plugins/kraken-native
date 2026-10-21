package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.Point;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.GraphicsObject;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphics objects playing in the scene (spell splashes, cannon smoke...): a live table of each one's id, tile, height,
 * start cycle and animation frame, and over the game its tile and a marker where it is drawn, with its id.
 */
final class GraphicsObjectsTool extends DevTool<Listing<GraphicsObjectsTool.Row>> {
    private static final Color COLOR = new Color(0xf0a0f0);
    private static final Color FILL = new Color(240, 160, 240, 90);
    private static final Color TILE = new Color(240, 160, 240, 150);

    /**
     * Half the marker's width, and how far above the marker the id goes, in client pixels.
     */
    private static final int MARKER = 5;
    private static final int LABEL_GAP = 12;

    private final ToolOption ids = option("ids", "Ids", true);
    private final ToolOption tiles = option("tiles", "Tiles", true);

    /**
     * One table row.
     *
     * @param key    identifies the object across polls
     * @param id     its spot anim id
     * @param tile   its world tile, e.g. "(3202, 3203, 0)"
     * @param x      its local x
     * @param y      its local y (north)
     * @param height the height it is drawn at
     * @param start  the cycle it starts playing on
     * @param frame  its animation frame, -1 before it starts
     * @param state  "waiting" (before its start cycle), "playing" or "ending" (animation over, dropped next frame)
     */
    record Row(String key, int id, String tile, int x, int y, int height, int start, int frame, String state) {
    }

    GraphicsObjectsTool() {
        super("graphics", "Graphics objects", Category.ACTORS, MaterialDesignS.SPARKLES,
                "Spot animations playing in the scene: spot anim id, tile, height, start cycle and animation frame.");
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
    protected Listing<Row> read(GameSession session) {
        Client client = session.client();
        Projector projector = isOverlayOn() ? Projector.of(session) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        int cycle = client.getGameCycle();
        List<Row> rows = new ArrayList<>();
        for (GraphicsObject g : client.getGraphicsObjects()) {
            LocalPoint at = g.getLocation();
            WorldPoint tile = WorldPoint.fromLocal(client.getTopLevelWorldView(), at.getX(), at.getY(), g.getLevel());
            String state = g.isAnimationEnded() ? "ending" : cycle >= 0 && cycle < g.getStartCycle() ? "waiting"
                    : "playing";
            rows.add(new Row(Long.toHexString(g.getAddress()) + ":" + g.getStartCycle(), g.getId(),
                    tile == null ? "" : "(" + tile.getX() + ", " + tile.getY() + ", " + tile.getPlane() + ")",
                    at.getX(), at.getY(), g.getZ(), g.getStartCycle(), g.getAnimationFrame(), state));
            if (marks != null) {
                mark(marks, projector, g);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, GraphicsObject g) {
        LocalPoint at = g.getLocation();
        if (tiles.isOn()) {
            marks.outline(projector.tile(g.getLevel(), at.getSceneX(), at.getSceneY()), TILE, null);
        }
        Point point = projector.at(at.getX(), at.getY(), g.getZ());
        if (point == null) {
            return;
        }
        marks.outline(new Point[]{new Point(point.getX(), point.getY() - MARKER),
                new Point(point.getX() + MARKER, point.getY()), new Point(point.getX(), point.getY() + MARKER),
                new Point(point.getX() - MARKER, point.getY())}, COLOR, FILL);
        if (ids.isOn()) {
            marks.label(String.valueOf(g.getId()), new Point(point.getX(), point.getY() - LABEL_GAP), COLOR);
        }
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("graphics")
                .column("Id", Integer.class, 56, Row::id)
                .column("Tile", String.class, 150, Row::tile)
                .column("X", Integer.class, 56, Row::x)
                .column("Y", Integer.class, 56, Row::y)
                .column("Height", Integer.class, 56, Row::height)
                .column("Start", Integer.class, 64, Row::start)
                .column("Frame", Integer.class, 56, Row::frame)
                .column("State", String.class, 64, Row::state)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
