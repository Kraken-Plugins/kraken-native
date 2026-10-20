package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.LayoutableRenderableEntity;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * A panel line: text on the left and text on the right. Build it with
 * {@code LineComponent.builder().left("Ticks:").right("120").build()}.
 *
 * <p>A line too long for the panel doesn't wrap; it widens the panel instead.
 */
@Setter
@Builder
public class LineComponent implements LayoutableRenderableEntity {

    /**
     * The least space between the two texts.
     */
    private static final int GAP = 8;

    /**
     * The left text.
     */
    private String left;

    /**
     * The right text, right-aligned.
     */
    private String right;

    /**
     * The left text's colour.
     */
    @Builder.Default
    private Color leftColor = Color.WHITE;

    /**
     * The right text's colour.
     */
    @Builder.Default
    private Color rightColor = Color.WHITE;

    /**
     * The left text's font, or null for the panel's.
     */
    private Font leftFont;

    /**
     * The right text's font, or null for the panel's.
     */
    private Font rightFont;

    /**
     * The top-left corner to draw at; the panel sets it.
     */
    @Builder.Default
    private Point preferredLocation = new Point();

    /**
     * The width to lay out in; the panel sets it.
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
        String leftText = left == null ? "" : left;
        String rightText = right == null ? "" : right;
        Font leftShown = leftFont != null ? leftFont : graphics.getFont();
        Font rightShown = rightFont != null ? rightFont : graphics.getFont();
        FontMetrics leftMetrics = graphics.getFontMetrics(leftShown);
        FontMetrics rightMetrics = graphics.getFontMetrics(rightShown);
        int leftWidth = leftMetrics.stringWidth(leftText);
        int rightWidth = rightMetrics.stringWidth(rightText);
        int gap = leftText.isEmpty() || rightText.isEmpty() ? 0 : GAP;
        int width = Math.max(preferredSize.width, leftWidth + gap + rightWidth);
        int baseline = preferredLocation.y + Math.max(leftMetrics.getAscent(), rightMetrics.getAscent());

        TextComponent text = new TextComponent();
        text.setText(leftText);
        text.setColor(leftColor);
        text.setFont(leftShown);
        text.setPosition(new Point(preferredLocation.x, baseline));
        text.render(graphics);
        text.setText(rightText);
        text.setColor(rightColor);
        text.setFont(rightShown);
        text.setPosition(new Point(preferredLocation.x + width - rightWidth, baseline));
        text.render(graphics);

        Dimension dimension = new Dimension(width, Math.max(leftMetrics.getHeight(), rightMetrics.getHeight()));
        bounds.setLocation(preferredLocation);
        bounds.setSize(dimension);
        return dimension;
    }
}
