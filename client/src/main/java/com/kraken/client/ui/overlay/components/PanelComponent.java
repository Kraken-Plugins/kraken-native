package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.LayoutableRenderableEntity;
import lombok.Getter;
import lombok.Setter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * A box of components (titles, lines) on a translucent background. Add children each frame and render it;
 * {@code OverlayPanel} does both for an overlay.
 *
 * <p>Children don't wrap onto a new row or column. The background fits this frame's children rather than last frame's:
 * the children are measured first (drawn with nothing visible), then drawn.
 */
public class PanelComponent implements LayoutableRenderableEntity {

    /**
     * Where it was last drawn.
     */
    @Getter
    private final Rectangle bounds = new Rectangle();

    /**
     * The components inside, in order.
     */
    @Getter
    private final List<LayoutableRenderableEntity> children = new ArrayList<>();

    /**
     * The top-left corner to draw at.
     */
    @Setter
    private Point preferredLocation = new Point();

    /**
     * The size to lay the children out in: their width, for a vertical panel. It grows to fit a wider child.
     */
    @Getter
    @Setter
    private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

    /**
     * The background colour, or null for none.
     */
    @Setter
    private Color backgroundColor = ComponentConstants.STANDARD_BACKGROUND_COLOR;

    /**
     * Which way the children go.
     */
    @Setter
    private ComponentOrientation orientation = ComponentOrientation.VERTICAL;

    /**
     * The space inside the edge: left in {@code x}, top in {@code y}, right in {@code width}, bottom in
     * {@code height}.
     */
    @Setter
    private Rectangle border = new Rectangle(ComponentConstants.STANDARD_BORDER, ComponentConstants.STANDARD_BORDER,
            ComponentConstants.STANDARD_BORDER, ComponentConstants.STANDARD_BORDER);

    /**
     * The space between children: across in {@code x}, down in {@code y}.
     */
    @Setter
    private Point gap = new Point(0, 1);

    @Override
    public Dimension render(Graphics2D graphics) {
        if (children.isEmpty()) {
            return null;
        }
        boolean vertical = orientation == ComponentOrientation.VERTICAL;
        int inner = vertical ? preferredSize.width - border.x - border.width
                : preferredSize.height - border.y - border.height;

        Graphics2D measure = (Graphics2D) graphics.create();
        Dimension content;
        try {
            measure.setClip(0, 0, 0, 0);
            content = layOut(measure, inner);
        } finally {
            measure.dispose();
        }
        int fitted = Math.max(inner, vertical ? content.width : content.height);
        Dimension dimension = new Dimension(border.x + (vertical ? fitted : content.width) + border.width,
                border.y + (vertical ? content.height : fitted) + border.height);

        if (backgroundColor != null) {
            BackgroundComponent background = new BackgroundComponent();
            background.setBackgroundColor(backgroundColor);
            background.setRectangle(new Rectangle(preferredLocation, dimension));
            background.render(graphics);
        }
        layOut(graphics, fitted);
        bounds.setLocation(preferredLocation);
        bounds.setSize(dimension);
        return dimension;
    }

    /**
     * Places and draws the children.
     *
     * @param graphics where to draw them
     * @param inner    the width (vertical) or height (horizontal) to give each child
     * @return the space they took
     */
    private Dimension layOut(Graphics2D graphics, int inner) {
        boolean vertical = orientation == ComponentOrientation.VERTICAL;
        int baseX = preferredLocation.x + border.x;
        int baseY = preferredLocation.y + border.y;
        int width = 0;
        int height = 0;
        boolean any = false;
        for (LayoutableRenderableEntity child : children) {
            child.setPreferredSize(vertical ? new Dimension(inner, 0) : new Dimension(0, inner));
            child.setPreferredLocation(vertical ? new Point(baseX, baseY + height) : new Point(baseX + width, baseY));
            Dimension size = child.render(graphics);
            if (size == null) {
                continue;
            }
            any = true;
            if (vertical) {
                height += size.height + gap.y;
                width = Math.max(width, size.width);
            } else {
                width += size.width + gap.x;
                height = Math.max(height, size.height);
            }
        }
        if (any) {
            if (vertical) {
                height -= gap.y;
            } else {
                width -= gap.x;
            }
        }
        return new Dimension(width, height);
    }
}
