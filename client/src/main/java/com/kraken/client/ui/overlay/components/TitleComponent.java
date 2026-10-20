package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.LayoutableRenderableEntity;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * A panel's title: one line of text centred in the panel's width, as RuneLite's {@code TitleComponent}. Build it with
 * {@code TitleComponent.builder().text("…").color(…).build()}.
 */
@Setter
@Builder
public class TitleComponent implements LayoutableRenderableEntity {

    /**
     * The title.
     */
    private String text;

    /**
     * The title's colour.
     */
    @Builder.Default
    private Color color = Color.WHITE;

    /**
     * The top-left corner to draw at; the panel sets it.
     */
    @Builder.Default
    private Point preferredLocation = new Point();

    /**
     * The width to centre in; the panel sets it.
     */
    @Builder.Default
    private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

    /**
     * Where it was last drawn.
     */
    @Getter
    @Builder.Default
    private final Rectangle bounds = new Rectangle();

    @Override
    public Dimension render(Graphics2D graphics) {
        String shown = text == null ? "" : text;
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(shown);
        int width = Math.max(preferredSize.width, textWidth);
        TextComponent title = new TextComponent();
        title.setText(shown);
        title.setColor(color);
        title.setPosition(new Point(preferredLocation.x + (width - textWidth) / 2,
                preferredLocation.y + metrics.getAscent()));
        title.render(graphics);
        Dimension dimension = new Dimension(width, metrics.getHeight());
        bounds.setLocation(preferredLocation);
        bounds.setSize(dimension);
        return dimension;
    }
}
