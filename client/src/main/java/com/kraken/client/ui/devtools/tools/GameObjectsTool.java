package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.DecorativeObject;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
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
import java.util.List;

/**
 * Scene objects around you (game objects, ground and wall decorations, and walls and doors too): a table of every
 * object within {@link #RADIUS} tiles, and over the game the outlines of those in the game object and decoration
 * layers, with their names and ids. Walls have their own tool.
 */
final class GameObjectsTool extends DevTool<Listing<GameObjectsTool.Row>> {
    static final int RADIUS = 15;
    private static final Color OBJECT = new Color(0xc896f0);
    private static final Color GROUND = new Color(0x78d778);
    private static final Color WALL_DECOR = new Color(0x64c8dc);

    /**
     * One object in the table. The tile is a world tile; the hash identifies the object.
     *
     * @param key      the object's layer, scene tile and id
     * @param id       the object type's id
     * @param name     its name
     * @param layer    its layer
     * @param x        the world x
     * @param y        the world y
     * @param plane    the plane
     * @param distance tiles from you
     * @param ops      its non-empty ops
     * @param hash     the object's hash
     * @param local    its local location, "x, y"
     * @param size     a game object's footprint, "x x y", or ""
     */
    record Row(String key, int id, String name, String layer, int x, int y, int plane, Integer distance, String ops,
               String hash, String local, String size) {
    }

    private final ToolOption objects = option("objects", "Game objects", true);
    private final ToolOption ground = option("ground", "Ground decorations", false);
    private final ToolOption wallDecor = option("wallDecor", "Wall decorations", false);
    private final ToolOption names = option("names", "Names", true);
    private final ToolOption ids = option("ids", "Ids", true);

    GameObjectsTool() {
        super("gameObjects", "Game objects", Category.SCENE, MaterialDesignC.CUBE_OUTLINE,
                "Scenery within " + RADIUS + " tiles: game objects such as trees, rocks and tables, ground and wall "
                        + "decorations, and walls and doors: with id, name, ops, hash, local location and size.");
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
        WorldPoint here = localTile(client);
        if (here == null) {
            return null;
        }
        List<TileObject> objects = Scenery.objectsAround(client, RADIUS);
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        List<Row> rows = new ArrayList<>(objects.size());
        for (TileObject object : objects) {
            Tile tile = object.getTile();
            Point scene = tile.getSceneLocation();
            WorldPoint at = object.getWorldLocation();
            String layer = Scenery.layer(object);
            String name = Scenery.name(client, object.getId());
            String key = layer + ":" + scene.getX() + ":" + scene.getY() + ":" + object.getId();
            rows.add(new Row(key, object.getId(), name, layer, at.getX(), at.getY(),
                    at.getPlane(), NpcsTool.distance(here, at), Scenery.ops(client, object.getId()),
                    "0x" + Long.toHexString(object.getHash()), Scenery.local(object), size(object)));
            if (marks != null) {
                mark(marks, projector, object, name);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    /**
     * A game object's footprint.
     *
     * @param object the object
     * @return "2 x 1" for a game object, "" for the others
     */
    private static String size(TileObject object) {
        return object instanceof GameObject game ? game.sizeX() + " x " + game.sizeY() : "";
    }

    private void mark(Marks.Builder marks, Projector projector, TileObject object, String name) {
        Color color = switch (object) {
            case GameObject game -> objects.isOn() ? OBJECT : null;
            case GroundObject floor -> ground.isOn() ? GROUND : null;
            case DecorativeObject decor -> wallDecor.isOn() ? WALL_DECOR : null;
            case WallObject wall -> null;
        };
        if (color == null) {
            return;
        }
        Tile tile = object.getTile();
        Point scene = tile.getSceneLocation();
        marks.outline(projector.tile(tile.getPlane(), scene.getX(), scene.getY()), color, null);
        marks.label(label(name, object.getId()),
                projector.tileCenter(tile.getPlane(), scene.getX(), scene.getY(), 0), color);
    }

    private String label(String name, int id) {
        String text = names.isOn() && !name.isEmpty() && !name.equals("null") ? name : "";
        if (ids.isOn()) {
            text = text.isEmpty() ? String.valueOf(id) : text + " (" + id + ")";
        }
        return text;
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("objects")
                .column("Id", Integer.class, 60, Row::id)
                .column("Name", String.class, 150, Row::name)
                .column("Layer", String.class, 100, Row::layer)
                .column("X", Integer.class, 46, Row::x)
                .column("Y", Integer.class, 46, Row::y)
                .column("Plane", Integer.class, 46, Row::plane)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Local", String.class, 100, Row::local)
                .column("Size", String.class, 60, Row::size)
                .column("Hash", String.class, 150, Row::hash)
                .column("Ops", String.class, 200, Row::ops)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
