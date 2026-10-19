package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.config.LocType;
import com.kraken.api.game.Client;
import com.kraken.api.game.Loc;
import com.kraken.api.game.MapCoord;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.MappedView;
import com.kraken.client.devtools.view.TableView;
import com.kraken.client.devtools.view.ToolView;
import com.kraken.client.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Locs (scenery) around you: a table of every loc within {@link #RADIUS} tiles, and over the game the game objects,
 * ground and wall decorations among them, outlined with their names and ids. Walls have their own tool.
 */
final class GameObjectsTool extends DevTool<Listing<GameObjectsTool.Row>> {
    static final int RADIUS = 15;
    private static final Color OBJECT = new Color(0xc896f0);
    private static final Color GROUND = new Color(0x78d778);
    private static final Color WALL_DECOR = new Color(0x64c8dc);

    record Row(String key, int id, String name, String layer, int x, int z, int level, Integer distance, String ops) {
    }

    private final ToolOption objects = option("objects", "Game objects", true);
    private final ToolOption ground = option("ground", "Ground decorations", false);
    private final ToolOption wallDecor = option("wallDecor", "Wall decorations", false);
    private final ToolOption names = option("names", "Names", true);
    private final ToolOption ids = option("ids", "Ids", true);

    GameObjectsTool() {
        super("gameObjects", "Game objects", Category.SCENE, MaterialDesignC.CUBE_OUTLINE,
                "Scenery within " + RADIUS + " tiles: game objects such as trees, rocks and tables, ground and wall "
                        + "decorations (and, in the window, walls and doors), with id, name and ops.");
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
        MapCoord self = client.coord();
        if (self == null) {
            return null;
        }
        List<Loc> locs = api.scene().getLocs(self, RADIUS);
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        List<Row> rows = new ArrayList<>(locs.size());
        for (Loc loc : locs) {
            LocType type = api.configs().loc(loc.id());
            String name = type == null ? "" : type.getName();
            MapCoord at = loc.coord();
            rows.add(new Row(loc.layer() + ":" + at.x() + ":" + at.z() + ":" + loc.id(), loc.id(), name, loc.layerName(),
                    at.x(), at.z(), at.level(), NpcsTool.distance(self, at), ops(type)));
            if (marks != null) {
                mark(marks, projector, loc, name);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    private void mark(Marks.Builder marks, Projector projector, Loc loc, String name) {
        Color color = switch (loc.layer()) {
            case Loc.GAME_OBJECT -> objects.isOn() ? OBJECT : null;
            case Loc.GROUND_DECOR -> ground.isOn() ? GROUND : null;
            case Loc.WALL_DECOR -> wallDecor.isOn() ? WALL_DECOR : null;
            default -> null;
        };
        if (color == null) {
            return;
        }
        marks.outline(projector.tile(loc.coord()), color, null);
        marks.label(label(name, loc.id()), projector.tileCenter(loc.coord(), 0), color);
    }

    private String label(String name, int id) {
        String text = names.isOn() && !name.isEmpty() && !name.equals("null") ? name : "";
        if (ids.isOn()) {
            text = text.isEmpty() ? String.valueOf(id) : text + " (" + id + ")";
        }
        return text;
    }

    private static String ops(LocType type) {
        if (type == null) {
            return "";
        }
        List<String> ops = new ArrayList<>();
        for (String op : type.getOps()) {
            if (!op.isEmpty()) {
                ops.add(op);
            }
        }
        return String.join(", ", ops);
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("locs")
                .column("Id", Integer.class, 60, Row::id)
                .column("Name", String.class, 150, Row::name)
                .column("Layer", String.class, 100, Row::layer)
                .column("X", Integer.class, 46, Row::x)
                .column("Z", Integer.class, 46, Row::z)
                .column("Plane", Integer.class, 46, Row::level)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Ops", String.class, 200, Row::ops)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
