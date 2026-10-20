package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.DecorativeObject;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Formatting and lookups shared by the tools that list scene objects and items.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class Scenery {
    /**
     * The scene objects on the tiles around you, in the top-level scene.
     *
     * @param client the client
     * @param radius how many tiles out from your tile, on each side
     * @return the objects, or an empty list when you aren't loaded
     */
    static List<TileObject> objectsAround(Client client, int radius) {
        Area area = area(client, radius);
        return area == null ? List.of()
                : client.getScene().getTileObjects(area.plane(), area.minX(), area.minY(), area.maxX(), area.maxY());
    }

    /**
     * The ground items on the tiles around you, in the top-level scene.
     *
     * @param client the client
     * @param radius how many tiles out from your tile, on each side
     * @return the items, or an empty list when you aren't loaded
     */
    static List<TileItem> itemsAround(Client client, int radius) {
        Area area = area(client, radius);
        return area == null ? List.of()
                : client.getScene().getGroundItems(area.plane(), area.minX(), area.minY(), area.maxX(), area.maxY());
    }

    /**
     * A square of scene tiles on one plane.
     *
     * @param plane the plane
     * @param minX  the lowest scene x
     * @param minY  the lowest scene y
     * @param maxX  the highest scene x
     * @param maxY  the highest scene y
     */
    private record Area(int plane, int minX, int minY, int maxX, int maxY) {
    }

    /**
     * The square of scene tiles around you, on your plane.
     *
     * @param client the client
     * @param radius the radius in tiles
     * @return the square, or null when you aren't loaded
     */
    private static Area area(Client client, int radius) {
        Player self = client.getLocalPlayer();
        LocalPoint local = self == null ? null : self.getLocalLocation();
        WorldPoint world = self == null ? null : self.getWorldLocation();
        if (local == null || world == null) {
            return null;
        }
        int x = local.getSceneX();
        int y = local.getSceneY();
        return new Area(world.getPlane(), x - radius, y - radius, x + radius, y + radius);
    }

    /**
     * The layer a scene object sits in, as the tool tables name it.
     *
     * @param object the object
     * @return "wall", "wall decor", "game object" or "ground decor"
     */
    static String layer(TileObject object) {
        return switch (object) {
            case WallObject wall -> "wall";
            case DecorativeObject decor -> "wall decor";
            case GameObject game -> "game object";
            case GroundObject ground -> "ground decor";
        };
    }

    /**
     * An object type's name.
     *
     * @param client the client
     * @param id     the object type's id
     * @return the name, or "" if it has none
     */
    static String name(Client client, int id) {
        return Objects.requireNonNullElse(client.getObjectDefinition(id).getName(), "");
    }

    /**
     * An object type's non-empty ops, joined.
     *
     * @param client the client
     * @param id     the object type's id
     * @return e.g. "Chop down, Examine"
     */
    static String ops(Client client, int id) {
        String[] actions = client.getObjectDefinition(id).getActions();
        return actions == null ? "" : String.join(", ",
                Arrays.stream(actions).filter(op -> op != null && !op.isEmpty()).toList());
    }

    /**
     * An object's local location, for display.
     *
     * @param object the object
     * @return "x, y" in local units, or "" if unknown
     */
    static String local(TileObject object) {
        LocalPoint local = object.getLocalLocation();
        return local == null ? "" : local.getX() + ", " + local.getY();
    }
}
