package com.kraken.client.overlay;

import com.kraken.api.game.Vector2;
import com.kraken.client.ui.Theme;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.Map;

/**
 * What a painter gets for one overlay frame: the graphics, the size of the game area, and helpers for common
 * overlay parts. Coordinates are UI pixels with (0, 0) at the game's top-left corner. Positions the API projects
 * ({@code Graphics.worldToScreenCoord}) are in the client's own pixels; {@link #toOverlay} converts them.
 */
public final class OverlayContext {
    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int PAD = 8;
    private static final Color PANEL = new Color(20, 20, 20, 210);
    private static final Color EDGE = new Color(255, 255, 255, 30);
    private static final Color SHADOW = new Color(0, 0, 0, 200);

    @Getter
    private final Graphics2D graphics;
    @Getter
    private final int width;
    @Getter
    private final int height;
    private int nextPanelY = MARGIN;

    OverlayContext(Graphics2D graphics, int width, int height) {
        this.graphics = graphics;
        this.width = width;
        this.height = height;
    }

    /**
     * Converts a position in the client's own pixels (what {@code Graphics.worldToScreenCoord} returns) to overlay
     * pixels. The client fills the game area, so this scales by the overlay's size over the client's.
     *
     * @param point      the position in client pixels
     * @param clientSize the client's size ({@code Graphics.clientDimensions}) when the position was projected
     * @return the position in overlay pixels
     */
    public Point2D.Float toOverlay(Vector2 point, Vector2 clientSize) {
        float sx = clientSize.x() > 0 ? width / clientSize.x() : 1f;
        float sy = clientSize.y() > 0 ? height / clientSize.y() : 1f;
        return new Point2D.Float(point.x() * sx, point.y() * sy);
    }

    /**
     * Draws a line through projected points, optionally closed and filled (a tile outline).
     *
     * @param points     the points in client pixels
     * @param clientSize the client's size when they were projected
     * @param closed     join the last point back to the first
     * @param stroke     the line colour
     * @param fill       the fill colour for a closed shape, or null for none
     */
    public void path(Vector2[] points, Vector2 clientSize, boolean closed, Color stroke, Color fill) {
        if (points.length < 2) {
            return;
        }
        Path2D.Float path = new Path2D.Float();
        for (int i = 0; i < points.length; i++) {
            Point2D.Float p = toOverlay(points[i], clientSize);
            if (i == 0) {
                path.moveTo(p.x, p.y);
            } else {
                path.lineTo(p.x, p.y);
            }
        }
        if (closed) {
            path.closePath();
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (closed && fill != null) {
                g.setColor(fill);
                g.fill(path);
            }
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(stroke);
            g.draw(path);
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws a short text centred on a projected point, with a shadow so it reads over the game.
     *
     * @param text       the text
     * @param at         the point in client pixels
     * @param clientSize the client's size when it was projected
     * @param color      the text colour
     */
    public void label(String text, Vector2 at, Vector2 clientSize, Color color) {
        Point2D.Float p = toOverlay(at, clientSize);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Font font = UIManager.getFont("Label.font");
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int x = Math.round(p.x - metrics.stringWidth(text) / 2f);
            int y = Math.round(p.y + metrics.getAscent() / 2f - 1);
            g.setColor(SHADOW);
            g.drawString(text, x + 1, y + 1);
            g.setColor(color);
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws a titled box of label/value rows in the top-left corner, below any boxes already drawn this frame.
     *
     * @param title the box's title
     * @param rows  the label/value rows, in order
     */
    public void infoPanel(String title, Map<String, String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Font labelFont = UIManager.getFont("Label.font");
            Font titleFont = labelFont.deriveFont(Font.BOLD);
            Font valueFont = UIManager.getFont("monospaced.font").deriveFont(labelFont.getSize2D());
            FontMetrics titleMetrics = g.getFontMetrics(titleFont);
            FontMetrics labelMetrics = g.getFontMetrics(labelFont);
            FontMetrics valueMetrics = g.getFontMetrics(valueFont);

            int labelWidth = 0;
            int valueWidth = 0;
            for (Map.Entry<String, String> row : rows.entrySet()) {
                labelWidth = Math.max(labelWidth, labelMetrics.stringWidth(row.getKey()));
                valueWidth = Math.max(valueWidth, valueMetrics.stringWidth(row.getValue()));
            }
            int lineHeight = Math.max(labelMetrics.getHeight(), valueMetrics.getHeight());
            int w = Math.max(titleMetrics.stringWidth(title), labelWidth + 16 + valueWidth) + 2 * PAD;
            int h = PAD + titleMetrics.getHeight() + 2 + rows.size() * lineHeight + PAD;
            int x = MARGIN;
            int y = nextPanelY;
            nextPanelY += h + GAP;

            g.setColor(PANEL);
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(EDGE);
            g.drawRoundRect(x, y, w - 1, h - 1, 8, 8);

            int baseline = y + PAD + titleMetrics.getAscent();
            g.setFont(titleFont);
            g.setColor(Theme.ACCENT);
            g.drawString(title, x + PAD, baseline);

            baseline += titleMetrics.getDescent() + 2;
            for (Map.Entry<String, String> row : rows.entrySet()) {
                baseline += lineHeight;
                g.setFont(labelFont);
                g.setColor(Theme.LIGHT);
                g.drawString(row.getKey(), x + PAD, baseline - labelMetrics.getDescent());
                g.setFont(valueFont);
                g.setColor(Theme.TEXT);
                g.drawString(row.getValue(), x + w - PAD - valueMetrics.stringWidth(row.getValue()),
                        baseline - valueMetrics.getDescent());
            }
        } finally {
            g.dispose();
        }
    }
}
