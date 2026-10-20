package com.kraken.client.ui.overlay;

import lombok.extern.java.Log;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lays out and draws one frame of overlays into a graphics in canvas pixels, the part of RuneLite's
 * {@code OverlayRenderer} that doesn't depend on where the frame goes. Layers draw in order; within a layer, overlays
 * draw in {@link OverlayManager#OVERLAY_COMPARATOR} order. Dynamic overlays draw over the whole canvas; anchored ones are
 * placed at their corner by the size they last drew at and move the corner on for the next, as RuneLite places them.
 * Unlike RuneLite, an overlay that skipped frames (drew nothing) is placed by its last real size when it comes back,
 * rather than for one frame as if it had no size.
 *
 * <p>Differs from RuneLite: the anchors stack across layers (RuneLite starts each layer from bare corners, so two
 * layers' overlays at one corner overlap). Kraken draws every layer over the interfaces, where overlapping would hide
 * one of them.
 *
 * <p>Each overlay draws on its own copy of the graphics, clipped to the canvas, so one can't leave a transform or
 * colour behind for the next. An overlay that throws is logged once (until it draws again) and skipped.
 */
@Log(topic = "kraken")
final class OverlayCompositor {

    /**
     * The gap between the canvas edge and an anchored overlay.
     */
    static final int BORDER = 5;

    /**
     * The top-left anchor's gap from the top edge, leaving room for the game's hovered-option text.
     */
    static final int BORDER_TOP = BORDER + 15;

    /**
     * The gap between overlays stacked at one anchor.
     */
    static final int PADDING = 2;

    private static final OverlayLayer[] LAYERS = OverlayLayer.values();

    private final Font panelFont;
    private final Font dynamicFont;
    private final Set<Overlay> failing = ConcurrentHashMap.newKeySet();
    private final Map<Overlay, Dimension> lastSizes = new WeakHashMap<>();

    /**
     * Creates a compositor.
     *
     * @param panelFont   the font anchored overlays start with
     * @param dynamicFont the font dynamic overlays start with
     */
    OverlayCompositor(Font panelFont, Font dynamicFont) {
        this.panelFont = panelFont;
        this.dynamicFont = dynamicFont;
    }

    /**
     * Draws a frame. Not thread-safe: the renderer calls it on the poll thread only.
     *
     * @param graphics where to draw, in canvas pixels
     * @param overlays the overlays, in {@link OverlayManager#getOverlays()} order
     * @param width    the canvas's width
     * @param height   the canvas's height
     */
    void render(Graphics2D graphics, List<Overlay> overlays, int width, int height) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Grayscale text AA: subpixel AA needs an opaque background
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Corners corners = new Corners(width, height);
        for (OverlayLayer layer : LAYERS) {
            for (Overlay overlay : overlays) {
                if (overlay.getLayer() == layer) {
                    draw(graphics, overlay, corners, width, height);
                }
            }
        }
    }

    private void draw(Graphics2D graphics, Overlay overlay, Corners corners, int width, int height) {
        OverlayPosition position = OverlayManager.effectivePosition(overlay);
        Rectangle bounds = overlay.getBounds();
        Point preferred = overlay.getPreferredLocation();
        boolean anchored = position != OverlayPosition.DYNAMIC
                && (preferred == null || overlay.getPreferredPosition() != null);
        Point location;
        if (position == OverlayPosition.DYNAMIC) {
            location = new Point();
        } else if (anchored) {
            Point corner = corners.get(position);
            Dimension last = lastSizes.getOrDefault(overlay, new Dimension());
            location = new Point(corner.x + offsetX(position, last.width), corner.y + offsetY(position, last.height));
        } else {
            location = new Point(preferred);
        }

        Dimension size = null;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setClip(0, 0, width, height);
            g.setFont(position == OverlayPosition.DYNAMIC ? dynamicFont : panelFont);
            g.translate(location.x, location.y);
            bounds.setLocation(location);
            size = overlay.render(g);
            failing.remove(overlay);
        } catch (RuntimeException | LinkageError e) {
            if (failing.add(overlay)) {
                log.log(Level.WARNING, "Overlay " + overlay.getName() + " failed to draw", e);
            }
        } finally {
            g.dispose();
        }
        bounds.setSize(size == null ? new Dimension() : size);
        if (bounds.width + bounds.height > 0) {
            lastSizes.put(overlay, bounds.getSize());
        }
        if (anchored && bounds.width + bounds.height > 0) {
            corners.shift(position, bounds);
        }
    }

    /**
     * How far left of its anchor an overlay's left edge goes.
     *
     * @param position the anchor
     * @param width    the overlay's width
     * @return the offset
     */
    private static int offsetX(OverlayPosition position, int width) {
        return switch (position) {
            case TOP_CENTER -> -width / 2;
            case TOP_RIGHT, CANVAS_TOP_RIGHT, BOTTOM_RIGHT -> -width;
            default -> 0;
        };
    }

    /**
     * How far above its anchor an overlay's top edge goes.
     *
     * @param position the anchor
     * @param height   the overlay's height
     * @return the offset
     */
    private static int offsetY(OverlayPosition position, int height) {
        return switch (position) {
            case BOTTOM_LEFT, BOTTOM_RIGHT -> -height;
            default -> 0;
        };
    }

    /**
     * The anchors for one frame, each moved on as overlays stack at it.
     */
    private static final class Corners {
        private final Point topLeft;
        private final Point topCenter;
        private final Point topRight;
        private final Point bottomLeft;
        private final Point bottomRight;

        Corners(int width, int height) {
            topLeft = new Point(BORDER, BORDER_TOP);
            topCenter = new Point(width / 2, BORDER);
            topRight = new Point(width - BORDER, BORDER);
            bottomLeft = new Point(BORDER, height - BORDER);
            bottomRight = new Point(width - BORDER, height - BORDER);
        }

        Point get(OverlayPosition position) {
            return switch (position) {
                case TOP_CENTER -> topCenter;
                case TOP_RIGHT, CANVAS_TOP_RIGHT -> topRight;
                case BOTTOM_LEFT -> bottomLeft;
                case BOTTOM_RIGHT -> bottomRight;
                default -> topLeft;
            };
        }

        void shift(OverlayPosition position, Rectangle bounds) {
            switch (position) {
                case BOTTOM_LEFT -> bottomLeft.translate(bounds.width + PADDING, 0);
                case BOTTOM_RIGHT -> bottomRight.translate(-bounds.width - PADDING, 0);
                default -> get(position).translate(0, bounds.height + PADDING);
            }
        }
    }
}
