package com.kraken.client.ui.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.GroundObj;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Vector2;
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

    record Row(String key, int id, String name, int count, int x, int z, int level, Integer distance, long value) {
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
        ClientOp client = api.lua().clientOp();
        MapCoord self = client.coord();
        if (self == null) {
            return null;
        }
        Projector projector = isOverlayOn() ? Projector.of(api) : null;
        Marks.Builder marks = projector == null ? null : new Marks.Builder(projector);
        Map<MapCoord, Integer> stacked = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (GroundObj obj : api.lua().scene().getObjs(self, GameObjectsTool.RADIUS)) {
            MapCoord at = obj.coord();
            String name = client.ocName(obj.id());
            int line = stacked.merge(at, 1, Integer::sum) - 1;
            rows.add(new Row(at.x() + ":" + at.z() + ":" + line, obj.id(), name, obj.count(), at.x(), at.z(),
                    at.level(), NpcsTool.distance(self, at), (long) Math.max(0, client.ocCost(obj.id())) * obj.count()));
            if (marks != null) {
                if (line == 0 && tiles.isOn()) {
                    marks.outline(projector.tile(at), COLOR, null);
                }
                Vector2 anchor = projector.tileCenter(at, 0);
                String text = name + (obj.count() > 1 ? " ×" + obj.count() : "") + (ids.isOn() ? " (" + obj.id() + ")" : "");
                marks.label(text, anchor == null ? null : new Vector2(anchor.x(), anchor.y() - line * LINE), COLOR);
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
                .column("Z", Integer.class, 46, Row::z)
                .column("Plane", Integer.class, 46, Row::level)
                .column("Dist", Integer.class, 46, Row::distance)
                .column("Value", Long.class, 80, Row::value)
                .key(Row::key)
                .build(), Listing::rows);
    }
}
