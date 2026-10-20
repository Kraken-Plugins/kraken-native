package com.kraken.client.ui.devtools.tools;

import com.kraken.api.GameObject;
import com.kraken.api.KrakenApi;
import com.kraken.api.Scene;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.Loc;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.types.LocType;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Locs (scenery) around you: a table of every loc within {@link #RADIUS} tiles, and over the game the game objects,
 * ground and wall decorations among them, outlined with their names and ids. Walls have their own tool.
 *
 * <p>The RuneLite view adds, for the selected loc only, the {@code TileObject} the RuneLite-shaped API reads on the
 * same scene tile ({@code Scene.getTile}): its hash, world and local location, and a game object's size.
 */
final class GameObjectsTool extends DevTool<Listing<GameObjectsTool.Row>> {
    static final int RADIUS = 15;
    private static final Color OBJECT = new Color(0xc896f0);
    private static final Color GROUND = new Color(0x78d778);
    private static final Color WALL_DECOR = new Color(0x64c8dc);

    /**
     * One loc in the table.
     *
     * @param key      the loc's slot and id
     * @param id       the loc type id
     * @param name     its name
     * @param layer    its layer's name
     * @param x        the scene tile x
     * @param z        the scene tile z
     * @param level    the scene level
     * @param distance tiles from you
     * @param ops      its non-empty ops
     * @param runelite what the RuneLite-shaped API reads for it; "" unless it is the selected one and the RuneLite view
     *                 is on
     */
    record Row(String key, int id, String name, String layer, int x, int z, int level, Integer distance, String ops,
               String runelite) {
    }

    private final ToolOption objects = option("objects", "Game objects", true);
    private final ToolOption ground = option("ground", "Ground decorations", false);
    private final ToolOption wallDecor = option("wallDecor", "Wall decorations", false);
    private final ToolOption names = option("names", "Names", true);
    private final ToolOption ids = option("ids", "Ids", true);
    private final ToolOption runelite = option("runelite", "RuneLite view", false);
    /**
     * The selected row's key, chosen in the window (EDT) and read on the poll thread.
     */
    private volatile String selected;

    GameObjectsTool() {
        super("gameObjects", "Game objects", Category.SCENE, MaterialDesignC.CUBE_OUTLINE,
                "Scenery within " + RADIUS + " tiles: game objects such as trees, rocks and tables, ground and wall "
                        + "decorations (and, in the window, walls and doors), with id, name and ops. The RuneLite view "
                        + "adds the selected one's TileObject hash, WorldPoint, LocalPoint and size.");
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
        MapCoord self = client.coord();
        if (self == null) {
            return null;
        }
        List<Loc> locs = api.lua().scene().getLocs(self, RADIUS);
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        List<Row> rows = new ArrayList<>(locs.size());
        String selectedKey = runelite.isOn() ? selected : null;
        for (Loc loc : locs) {
            LocType type = api.lua().configs().loc(loc.id());
            String name = type == null ? "" : type.getName();
            MapCoord at = loc.coord();
            String key = loc.layer() + ":" + at.x() + ":" + at.z() + ":" + loc.id();
            String rl = key.equals(selectedKey) ? runeLite(api, loc) : "";
            rows.add(new Row(key, loc.id(), name, loc.layerName(), at.x(), at.z(), at.level(),
                    NpcsTool.distance(self, at), ops(type), rl));
            if (marks != null) {
                mark(marks, projector, loc, name);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    /**
     * What the RuneLite-shaped API reads for a loc: the {@code TileObject} in the same layer of the same scene tile.
     *
     * @param api the API
     * @param loc the loc, from the Lua mirror's listing
     * @return the RuneLite view's text, or "?" if the object can't be found
     */
    private static String runeLite(KrakenApi api, Loc loc) {
        MapCoord at = loc.coord();
        Scene scene = api.client().getScene();
        Tile tile = scene == null ? null : scene.getTile(at.level(), at.x(), at.z());
        if (tile == null) {
            return "?";
        }
        TileObject object = switch (loc.layer()) {
            case Loc.WALL -> tile.getWallObject();
            case Loc.WALL_DECOR -> tile.getDecorativeObject();
            case Loc.GROUND_DECOR -> tile.getGroundObject();
            default -> Arrays.stream(tile.getGameObjects()).filter(o -> o.getId() == loc.id()).findFirst()
                    .orElse(null);
        };
        if (object == null) {
            return "?";
        }
        WorldPoint world = object.getWorldLocation();
        LocalPoint local = object.getLocalLocation();
        String text = "hash 0x" + Long.toHexString(object.getHash()) + "  world " + world.getX() + ", " + world.getY()
                + ", " + world.getPlane() + "  local " + local.getX() + ", " + local.getY();
        return object instanceof GameObject game ? text + "  size " + game.sizeX() + "×" + game.sizeY() : text;
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
                .column("RuneLite", String.class, 380, Row::runelite)
                .key(Row::key)
                .onSelect(row -> selected = row == null ? null : row.key())
                .build(), Listing::rows);
    }
}
