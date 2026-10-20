package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.RenderableEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * A panel's background: a fill with a dark outer edge and a light inner one, both derived from the fill colour.
 */
@Getter
@Setter
@NoArgsConstructor
public class BackgroundComponent implements RenderableEntity {

    private static final int EDGE_SHIFT = 15;

    /**
     * The fill colour.
     */
    private Color backgroundColor = ComponentConstants.STANDARD_BACKGROUND_COLOR;

    /**
     * The area to cover.
     */
    private Rectangle rectangle = new Rectangle();

    /**
     * Whether to fill the area, or only draw its edges.
     */
    private boolean fill = true;

    @Override
    public Dimension render(Graphics2D graphics) {
        Color outer = new Color(Math.max(0, backgroundColor.getRed() - EDGE_SHIFT),
                Math.max(0, backgroundColor.getGreen() - EDGE_SHIFT),
                Math.max(0, backgroundColor.getBlue() - EDGE_SHIFT));
        Color inner = new Color(Math.min(255, backgroundColor.getRed() + 20),
                Math.min(255, backgroundColor.getGreen() + 20),
                Math.min(255, backgroundColor.getBlue() + 20));
        if (fill) {
            graphics.setColor(backgroundColor);
            graphics.fill(rectangle);
        }
        graphics.setColor(outer);
        graphics.drawRect(rectangle.x, rectangle.y, rectangle.width - 1, rectangle.height - 1);
        graphics.setColor(inner);
        graphics.drawRect(rectangle.x + 1, rectangle.y + 1, rectangle.width - 3, rectangle.height - 3);
        return rectangle.getSize();
    }
}
