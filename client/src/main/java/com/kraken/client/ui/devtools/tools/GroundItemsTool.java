package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.MappedView;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Items on the ground around you: a table, and over the game a label per item stacked on its tile.
 */
final class GroundItemsTool extends DevTool<Listing<GroundItemsTool.Row>> {
    private static final Color COLOR = new Color(0xf0f0a0);
    private static final int LINE = 14;

    /**
     * One pile's row. The tile is a world tile.
     *
     * @param key   a key unique among the rows
     * @param id    the item's id
     * @param name  its name
     * @param count the stack's quantity
     * @param x     the world x
     * @param y     the world y
     * @param plane the plane
     * @param distance tiles from you, or null
     * @param value the stack's value: its price times its quantity
     */
    record Row(String key, int id, String name, int count, int x, int y, int plane, Integer distance, long value) {
    }

    private final ToolOption ids = option("ids", "Ids", false);
    private final ToolOption tiles = option("tiles", "Tiles", true);

    GroundItemsTool() {
        super("groundItems", "Ground items", Category.SCENE, MaterialDesignP.PACKAGE_VARIANT_CLOSED,
                "Items on the ground within " + GameObjectsTool.RADIUS + " tiles, with id, name, quantity and value.");
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
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        Map<Tile, Integer> stacked = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (TileItem item : Scenery.itemsAround(client, GameObjectsTool.RADIUS)) {
            Tile tile = item.getTile();
            Point scene = tile.getSceneLocation();
            WorldPoint at = tile.getWorldLocation();
            String name = client.getItemDefinition(item.getId()).getName();
            int line = stacked.merge(tile, 1, Integer::sum) - 1;
            rows.add(new Row(scene.getX() + ":" + scene.getY() + ":" + tile.getPlane() + ":" + line, item.getId(),
                    name, item.getQuantity(), at.getX(), at.getY(), at.getPlane(), NpcsTool.distance(here, at),
                    (long) Math.max(0, client.getItemDefinition(item.getId()).getPrice()) * item.getQuantity()));
            if (marks != null) {
                if (line == 0 && tiles.isOn()) {
                    marks.outline(projector.tile(tile.getPlane(), scene.getX(), scene.getY()), COLOR, null);
                }
                Point anchor = projector.tileCenter(tile.getPlane(), scene.getX(), scene.getY(), 0);
                String text = name + (item.getQuantity() > 1 ? " ×" + item.getQuantity() : "")
                        + (ids.isOn() ? " (" + item.getId() + ")" : "");
                marks.label(text, anchor == null ? null : new Point(anchor.getX(), anchor.getY() - line * LINE),
                        COLOR);
            }
        }
        return new Listing<>(rows, marks == null ? Marks.NONE : marks.build());
    }

    @Override
    protected void paintOverlay(Listing<Row> snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Listing<Row>> createView() {
        return new MappedView<>(TableView.<Row>builder("ground items")
                .column("Id", Integer.class, 60, Row::id)
                .column("Name", String.class, 160, Row::name)
                .column("Qty", Integer.class, 60, Row::count)
                .column("X", Integer.class, 46, Row::x)
                .column("Y", Integer.class, 46, Row::y)
                .column("Plane", Integer.class, 46, Row::plane)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Value", Long.class, 80, Row::value)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
