package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.awt.*;

/**
 * Walls (and doors, fences...) around you, outlined over the game with their ids.
 */
final class WallsTool extends DevTool<Marks> {
    private static final Color COLOR = new Color(0xf09650);
    private static final Color FILL = new Color(240, 150, 80, 25);

    private final ToolOption ids = option("ids", "Ids", true);

    WallsTool() {
        super("walls", "Walls", Category.SCENE, MaterialDesignW.WALL,
                "Wall objects (walls, doors, fences) within " + GameObjectsTool.RADIUS + " tiles, with their ids.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    protected Marks read(KrakenApi api) {
        Client client = api.client();
        Projector projector = Projector.of(api);
        if (projector == null) {
            return null;
        }
        Marks.Builder marks = new Marks.Builder(projector);
        for (TileObject object : Scenery.objectsAround(client, GameObjectsTool.RADIUS)) {
            if (object instanceof WallObject) {
                Tile tile = object.getTile();
                Point scene = tile.getSceneLocation();
                marks.outline(projector.tile(tile.getPlane(), scene.getX(), scene.getY()), COLOR, FILL);
                if (ids.isOn()) {
                    marks.label(String.valueOf(object.getId()),
                            projector.tileCenter(tile.getPlane(), scene.getX(), scene.getY(), 0), COLOR);
                }
            }
        }
        return marks.build();
    }

    @Override
    protected void paintOverlay(Marks marks, OverlayContext overlay) {
        marks.paint(overlay);
    }
}
