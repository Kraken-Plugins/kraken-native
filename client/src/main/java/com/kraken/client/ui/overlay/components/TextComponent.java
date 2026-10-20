package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.RenderableEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;

/**
 * One piece of text with a drop shadow, so it reads over the game, as RuneLite's {@code TextComponent}.
 *
 * <p>Reduced from RuneLite's: no {@code <col=...>} colour tags and no outline.
 */
@Getter
@Setter
@NoArgsConstructor
public class TextComponent implements RenderableEntity {

    /**
     * The text.
     */
    private String text = "";

    /**
     * Where the baseline starts.
     */
    private Point position = new Point();

    /**
     * The text colour.
     */
    private Color color = Color.WHITE;

    /**
     * The font, or null for the graphics' own.
     */
    private Font font;

    @Override
    public Dimension render(Graphics2D graphics) {
        Font original = graphics.getFont();
        if (font != null) {
            graphics.setFont(font);
        }
        try {
            FontMetrics metrics = graphics.getFontMetrics();
            String shown = text == null ? "" : text;
            graphics.setColor(Color.BLACK);
            graphics.drawString(shown, position.x + 1, position.y + 1);
            graphics.setColor(color);
            graphics.drawString(shown, position.x, position.y);
            return new Dimension(metrics.stringWidth(shown), metrics.getHeight());
        } finally {
            graphics.setFont(original);
        }
    }
}
