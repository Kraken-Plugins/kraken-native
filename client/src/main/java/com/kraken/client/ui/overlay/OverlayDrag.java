package com.kraken.client.ui.overlay;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * One drag of an overlay by the user, in canvas pixels: a move, or a resize by one edge or two meeting at a corner.
 * The edge that is dragged follows the pointer and the opposite one stays put; a moved or resized overlay is kept on
 * the canvas.
 *
 * @param overlay the overlay
 * @param start   where it was drawn when the drag started
 * @param from    where the pointer went down
 * @param edges   the edges being dragged ({@link #LEFT}, {@link #RIGHT}, {@link #TOP}, {@link #BOTTOM} or'd together),
 *                or 0 to move the overlay
 * @param canvas  the canvas's size when the drag started
 */
record OverlayDrag(Overlay overlay, Rectangle start, Point from, int edges, Dimension canvas) {

    /**
     * The left edge, in {@link #edges()}.
     */
    static final int LEFT = 1;

    /**
     * The right edge.
     */
    static final int RIGHT = 2;

    /**
     * The top edge.
     */
    static final int TOP = 4;

    /**
     * The bottom edge.
     */
    static final int BOTTOM = 8;

    /**
     * How far inside an edge the pointer can go down and still grab the edge, in canvas pixels. Narrow overlays get
     * narrower grips, so their middle can still be grabbed to move them.
     */
    static final int GRIP = 5;

    /**
     * The smallest the user can make an overlay, in canvas pixels, unless it is smaller already.
     */
    static final int MIN_SIZE = 32;

    /**
     * The edges a press grabs: those within a grip of it.
     *
     * @param bounds the overlay's bounds
     * @param at     where the pointer went down, inside {@code bounds}
     * @return the edges, or 0 if it's away from every edge, which moves the overlay
     */
    static int edgesAt(Rectangle bounds, Point at) {
        int gripX = Math.min(GRIP, bounds.width / 4);
        int gripY = Math.min(GRIP, bounds.height / 4);
        int edges = 0;
        if (at.x < bounds.x + gripX) {
            edges |= LEFT;
        } else if (at.x >= bounds.x + bounds.width - gripX) {
            edges |= RIGHT;
        }
        if (at.y < bounds.y + gripY) {
            edges |= TOP;
        } else if (at.y >= bounds.y + bounds.height - gripY) {
            edges |= BOTTOM;
        }
        return edges;
    }

    /**
     * The pointer shape over edges.
     *
     * @param edges the edges, as {@link #edgesAt} returns them
     * @return a {@link Cursor} type: a resize arrow for edges, the move arrows for none
     */
    static int cursor(int edges) {
        return switch (edges) {
            case LEFT -> Cursor.W_RESIZE_CURSOR;
            case RIGHT -> Cursor.E_RESIZE_CURSOR;
            case TOP -> Cursor.N_RESIZE_CURSOR;
            case BOTTOM -> Cursor.S_RESIZE_CURSOR;
            case LEFT | TOP -> Cursor.NW_RESIZE_CURSOR;
            case RIGHT | TOP -> Cursor.NE_RESIZE_CURSOR;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE_CURSOR;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE_CURSOR;
            default -> Cursor.MOVE_CURSOR;
        };
    }

    /**
     * Whether the drag resizes the overlay rather than moving it.
     *
     * @return true if it drags an edge
     */
    boolean resizing() {
        return edges != 0;
    }

    /**
     * Where the overlay goes with the pointer at a point.
     *
     * @param at where the pointer is now
     * @return the overlay's new bounds
     */
    Rectangle to(Point at) {
        int dx = at.x - from.x;
        int dy = at.y - from.y;
        if (edges == 0) {
            return new Rectangle(clamp(start.x + dx, 0, canvas.width - start.width),
                    clamp(start.y + dy, 0, canvas.height - start.height), start.width, start.height);
        }
        int left = start.x;
        int top = start.y;
        int right = start.x + start.width;
        int bottom = start.y + start.height;
        int minWidth = Math.min(MIN_SIZE, start.width);
        int minHeight = Math.min(MIN_SIZE, start.height);
        if ((edges & LEFT) != 0) {
            left = clamp(left + dx, 0, right - minWidth);
        }
        if ((edges & RIGHT) != 0) {
            right = clamp(right + dx, left + minWidth, Math.max(canvas.width, right));
        }
        if ((edges & TOP) != 0) {
            top = clamp(top + dy, 0, bottom - minHeight);
        }
        if ((edges & BOTTOM) != 0) {
            bottom = clamp(bottom + dy, top + minHeight, Math.max(canvas.height, bottom));
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    /**
     * Moves or resizes the overlay to follow the pointer. A dragged overlay leaves its anchor for a preferred location.
     * A resize sets the preferred size along the edges dragged; across the others it keeps the overlay's preferred
     * size, or else its drawn width and its natural height.
     *
     * @param at where the pointer is now
     */
    void dragTo(Point at) {
        Rectangle bounds = to(at);
        if (resizing()) {
            Dimension old = overlay.getPreferredSize();
            boolean across = (edges & (LEFT | RIGHT)) != 0;
            boolean down = (edges & (TOP | BOTTOM)) != 0;
            overlay.setPreferredSize(new Dimension(across ? bounds.width : old != null ? old.width : start.width,
                    down ? bounds.height : old != null ? old.height : 0));
        }
        overlay.setPreferredPosition(null);
        overlay.setPreferredLocation(bounds.getLocation());
    }

    /**
     * A value kept within bounds; the lower bound wins if the two cross.
     *
     * @param value the value
     * @param min   the least it may be
     * @param max   the most it may be
     * @return the clamped value
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
