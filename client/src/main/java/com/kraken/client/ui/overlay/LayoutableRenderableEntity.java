package com.kraken.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * A {@link RenderableEntity} its container places and sizes, as RuneLite's {@code LayoutableRenderableEntity}: a
 * panel's lines and titles, or an overlay.
 */
public interface LayoutableRenderableEntity extends RenderableEntity {

    /**
     * Where and how big it was last drawn.
     *
     * @return the bounds
     */
    Rectangle getBounds();

    /**
     * Where to draw it next: the top-left corner.
     *
     * @param position the corner, in the container's coordinates
     */
    void setPreferredLocation(Point position);

    /**
     * How big to draw it next. A width of a panel's line is the width to lay it out in; 0 means "as needed".
     *
     * @param dimension the size
     */
    void setPreferredSize(Dimension dimension);
}
