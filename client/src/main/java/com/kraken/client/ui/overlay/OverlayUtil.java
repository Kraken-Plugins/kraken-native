package com.kraken.client.ui.overlay;

import com.kraken.api.Actor;
import com.kraken.api.Client;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.TileObject;
import com.kraken.api.coords.LocalPoint;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

/**
 * Drawing helpers for overlays, as RuneLite's {@code OverlayUtil}, in canvas pixels. Reduced: the minimap helpers
 * ({@code renderMinimapLocation} and the minimap half of {@code renderTileOverlay}) wait for the minimap's position,
 * and the hover and clickbox helpers for the mouse and models, which Kraken doesn't read.
 */
public final class OverlayUtil {

    /**
     * How high text and images go above an actor. RuneLite places them by the actor's model height
     * ({@code getLogicalHeight() + 40}); Kraken doesn't read model heights, so every actor gets a little over a
     * player's.
     */
    public static final int ACTOR_OVERHEAD_OFFSET = 240;

    private static final Color DEFAULT_FILL = new Color(0, 0, 0, 50);

    private OverlayUtil() {
    }

    /**
     * Outlines a shape in a colour, with a 2-pixel line and a faint dark fill.
     *
     * @param graphics where to draw
     * @param shape    the shape, e.g. a tile from {@code Perspective.getCanvasTilePoly}
     * @param color    the outline colour
     */
    public static void renderPolygon(Graphics2D graphics, Shape shape, Color color) {
        renderPolygon(graphics, shape, color, new BasicStroke(2));
    }

    /**
     * Outlines a shape with a given line, and a faint dark fill.
     *
     * @param graphics where to draw
     * @param shape    the shape
     * @param color    the outline colour
     * @param stroke   the outline's line
     */
    public static void renderPolygon(Graphics2D graphics, Shape shape, Color color, Stroke stroke) {
        renderPolygon(graphics, shape, color, DEFAULT_FILL, stroke);
    }

    /**
     * Outlines and fills a shape.
     *
     * @param graphics where to draw
     * @param shape    the shape
     * @param color    the outline colour
     * @param fill     the fill colour
     * @param stroke   the outline's line
     */
    public static void renderPolygon(Graphics2D graphics, Shape shape, Color color, Color fill, Stroke stroke) {
        Stroke original = graphics.getStroke();
        graphics.setColor(color);
        graphics.setStroke(stroke);
        graphics.draw(shape);
        graphics.setColor(fill);
        graphics.fill(shape);
        graphics.setStroke(original);
    }

    /**
     * Draws text with a drop shadow, so it reads over the game. The colour is drawn opaque.
     *
     * @param graphics where to draw
     * @param location the baseline's left end, e.g. from {@code Actor.getCanvasTextLocation}
     * @param text     the text; nothing is drawn for null or ""
     * @param color    the text colour
     */
    public static void renderTextLocation(Graphics2D graphics, Point location, String text, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        graphics.setColor(Color.BLACK);
        graphics.drawString(text, location.getX() + 1, location.getY() + 1);
        graphics.setColor(new Color(color.getRGB() | 0xff000000, true));
        graphics.drawString(text, location.getX(), location.getY());
    }

    /**
     * Draws an image with its top-left corner at a point.
     *
     * @param graphics where to draw
     * @param location the top-left corner, e.g. from {@code Actor.getCanvasImageLocation}
     * @param image    the image
     */
    public static void renderImageLocation(Graphics2D graphics, Point location, BufferedImage image) {
        graphics.drawImage(image, location.getX(), location.getY(), null);
    }

    /**
     * Outlines the tiles an actor stands on and writes text over it.
     *
     * @param graphics where to draw
     * @param actor    the actor
     * @param text     the text, or null for none
     * @param color    the colour of both
     */
    public static void renderActorOverlay(Graphics2D graphics, Actor actor, String text, Color color) {
        Polygon tile = actor.getCanvasTilePoly();
        if (tile != null) {
            renderPolygon(graphics, tile, color);
        }
        renderActorTextOverlay(graphics, actor, text, color);
    }

    /**
     * Writes text over an actor, {@link #ACTOR_OVERHEAD_OFFSET} above the ground.
     *
     * @param graphics where to draw
     * @param actor    the actor
     * @param text     the text, or null for none
     * @param color    the text colour
     */
    public static void renderActorTextOverlay(Graphics2D graphics, Actor actor, String text, Color color) {
        if (text == null) {
            return;
        }
        Point location = actor.getCanvasTextLocation(graphics, text, ACTOR_OVERHEAD_OFFSET);
        if (location != null) {
            renderTextLocation(graphics, location, text, color);
        }
    }

    /**
     * Outlines the tiles an actor stands on and draws an image over it.
     *
     * @param graphics where to draw
     * @param actor    the actor
     * @param image    the image
     * @param color    the outline colour
     * @param zOffset  how far above the ground the image's centre goes, in local units
     */
    public static void renderActorOverlayImage(Graphics2D graphics, Actor actor, BufferedImage image, Color color,
                                               int zOffset) {
        Polygon tile = actor.getCanvasTilePoly();
        if (tile != null) {
            renderPolygon(graphics, tile, color);
        }
        Point location = actor.getCanvasImageLocation(image, zOffset);
        if (location != null) {
            renderImageLocation(graphics, location, image);
        }
    }

    /**
     * Outlines the tile an object is on (a game object's whole footprint) and writes text over it.
     *
     * @param graphics   where to draw
     * @param tileObject the object
     * @param text       the text, or null for none
     * @param color      the colour of both
     */
    public static void renderTileOverlay(Graphics2D graphics, TileObject tileObject, String text, Color color) {
        Polygon tile = tileObject.getCanvasTilePoly();
        if (tile != null) {
            renderPolygon(graphics, tile, color);
        }
        if (text != null) {
            Point location = tileObject.getCanvasTextLocation(graphics, text, 0);
            if (location != null) {
                renderTextLocation(graphics, location, text, color);
            }
        }
    }

    /**
     * Outlines the tile a point is on and draws an image centred on it.
     *
     * @param client        the client, for the camera
     * @param graphics      where to draw
     * @param localLocation a point on the tile (the outline is centred on it, so pass the tile's centre)
     * @param image         the image
     * @param color         the outline colour
     */
    public static void renderTileOverlay(Client client, Graphics2D graphics, LocalPoint localLocation,
                                         BufferedImage image, Color color) {
        Polygon tile = Perspective.getCanvasTilePoly(client, localLocation);
        if (tile != null) {
            renderPolygon(graphics, tile, color);
        }
        renderImageLocation(client, graphics, localLocation, image, 0);
    }

    /**
     * Draws an image centred over a point.
     *
     * @param client     the client, for the camera
     * @param graphics   where to draw
     * @param localPoint the point
     * @param image      the image
     * @param zOffset    how far above the ground the image's centre goes, in local units
     */
    public static void renderImageLocation(Client client, Graphics2D graphics, LocalPoint localPoint,
                                           BufferedImage image, int zOffset) {
        Point location = Perspective.getCanvasImageLocation(client, localPoint, image, zOffset);
        if (location != null) {
            renderImageLocation(graphics, location, image);
        }
    }
}
