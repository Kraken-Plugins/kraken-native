package com.kraken.client.overlay;

import com.kraken.client.ui.Theme;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * What a painter gets for one overlay frame: the graphics, the size of the game area, and helpers for common
 * overlay parts. Coordinates are UI pixels with (0, 0) at the game's top-left corner.
 */
public final class OverlayContext {
    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int PAD = 8;
    private static final Color PANEL = new Color(20, 20, 20, 210);
    private static final Color EDGE = new Color(255, 255, 255, 30);

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

    /** Draws a titled box of label/value rows in the top-left corner, below any boxes already drawn this frame. */
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
