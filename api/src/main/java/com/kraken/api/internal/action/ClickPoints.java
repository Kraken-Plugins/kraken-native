package com.kraken.api.internal.action;

import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.actor.Actor;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.Rectangle;

/**
 * Where a subject is on the game canvas, for the mouse click that goes before its action: about the middle of an
 * actor's model, a loc's centre, a ground item's or walk tile's centre, a widget's centre. Each is null while the
 * subject is off the canvas. Internal: {@link Interactions} uses them.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClickPoints {

    /**
     * How far above an actor's tile the click lands, in local units: about a model's middle.
     */
    public static final int ACTOR_HEIGHT = 100;

    /**
     * Where to click an actor: a point above the middle of its tile.
     *
     * @param client the client
     * @param actor  the actor
     * @return the canvas point, or null while the actor isn't on the canvas
     */
    public static Point actor(Client client, Actor actor) {
        LocalPoint local = actor.getLocalLocation();
        WorldPoint world = actor.getWorldLocation();
        if (local == null || world == null) {
            return null;
        }
        return onCanvas(client, Perspective.localToCanvas(client, local, world.getPlane(), ACTOR_HEIGHT));
    }

    /**
     * Where to click a loc: its centre, on the ground.
     *
     * @param client the client
     * @param object the loc
     * @return the canvas point, or null while it isn't on the canvas
     */
    public static Point object(Client client, TileObject object) {
        return onCanvas(client, object.getCanvasLocation());
    }

    /**
     * Where to click a ground item: its tile's centre.
     *
     * @param client the client
     * @param item   the item
     * @return the canvas point, or null while the tile isn't on the canvas
     */
    public static Point groundItem(Client client, TileItem item) {
        return onCanvas(client, Perspective.localToCanvas(client, item.getTile().getLocalLocation(),
                item.getTile().getPlane()));
    }

    /**
     * Where to click a tile: its centre.
     *
     * @param client the client
     * @param tile   the world tile
     * @return the canvas point, or null while the tile isn't in the scene or on the canvas
     */
    public static Point tile(Client client, WorldPoint tile) {
        LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), tile.getX(), tile.getY());
        return local == null ? null : onCanvas(client, Perspective.localToCanvas(client, local, tile.getPlane()));
    }

    /**
     * Where to click a widget: its centre.
     *
     * @param client the client
     * @param widget the widget
     * @return the canvas point, or null while the widget is hidden or off the canvas
     */
    public static Point widget(Client client, Widget widget) {
        Rectangle bounds = widget.isHidden() ? null : widget.getBounds();
        return bounds == null ? null
                : onCanvas(client, new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    }

    /**
     * A point if it is on the canvas.
     *
     * @param client the client, for the canvas size
     * @param point  the point, or null
     * @return the point, or null if it is null or outside the canvas
     */
    public static Point onCanvas(Client client, Point point) {
        if (point == null || point.getX() < 0 || point.getY() < 0 || point.getX() >= client.getCanvasWidth()
                || point.getY() >= client.getCanvasHeight()) {
            return null;
        }
        return point;
    }
}
