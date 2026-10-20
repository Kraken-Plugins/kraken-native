package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Point;
import com.kraken.client.ui.overlay.OverlayContext;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * What an overlay tool draws for one frame, worked out on the poll thread: outlines, lines and labels at points in the
 * client's own pixels, and the client's size they were projected for. Immutable.
 *
 * @param clientSize the client's size when the points were projected
 * @param marks      what to draw, in order
 */
record Marks(Dimension clientSize, List<Mark> marks) {

    /**
     * Nothing to draw.
     */
    static final Marks NONE = new Marks(new Dimension(1, 1), List.of());

    /**
     * One shape or label.
     *
     * @param points the outline or line, or null for a label alone
     * @param closed whether the outline closes back on itself
     * @param fill   fill colour for a closed outline, or null
     * @param text   a label, or null
     * @param at     where the label goes, or null
     * @param color  the line and label colour
     */
    record Mark(Point[] points, boolean closed, Color fill, String text, Point at, Color color) {
    }

    /**
     * Draws every mark.
     *
     * @param overlay the overlay frame
     */
    void paint(OverlayContext overlay) {
        for (Mark mark : marks) {
            if (mark.points() != null) {
                overlay.path(mark.points(), clientSize, mark.closed(), mark.color(), mark.fill());
            }
            if (mark.text() != null && mark.at() != null) {
                overlay.label(mark.text(), mark.at(), clientSize, mark.color());
            }
        }
    }

    /**
     * Collects marks for one frame. Points that couldn't be projected (null) are skipped.
     */
    static final class Builder {
        private final Dimension clientSize;
        private final List<Mark> marks = new ArrayList<>();

        Builder(Projector projector) {
            this(projector.clientSize());
        }

        /**
         * For marks that are already in client pixels, such as interface components.
         *
         * @param clientSize the client's size
         */
        Builder(Dimension clientSize) {
            this.clientSize = clientSize;
        }

        Builder outline(Point[] points, Color color, Color fill) {
            if (points != null) {
                marks.add(new Mark(points, true, fill, null, null, color));
            }
            return this;
        }

        Builder line(Point[] points, Color color) {
            if (points != null) {
                marks.add(new Mark(points, false, null, null, null, color));
            }
            return this;
        }

        Builder label(String text, Point at, Color color) {
            if (text != null && !text.isEmpty() && at != null) {
                marks.add(new Mark(null, false, null, text, at, color));
            }
            return this;
        }

        Marks build() {
            return new Marks(clientSize, List.copyOf(marks));
        }
    }
}
