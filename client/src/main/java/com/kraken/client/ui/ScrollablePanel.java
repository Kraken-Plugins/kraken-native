package com.kraken.client.ui;

import java.awt.*;
import javax.swing.*;

/**
 * A scroll view that always fits the sidebar's width, so rows wrap instead of scrolling sideways. Put the content in
 * its {@link BorderLayout#NORTH} so it stays at the top.
 */
public final class ScrollablePanel extends JPanel implements Scrollable {

    /**
     * Creates an empty view with a {@link BorderLayout}.
     */
    public ScrollablePanel() {
        super(new BorderLayout());
    }

    /**
     * Wraps content in a borderless vertical scroll pane that tracks the sidebar's width.
     *
     * @param content the rows to scroll
     * @return the scroll pane
     */
    public static JScrollPane scroll(JComponent content) {
        ScrollablePanel view = new ScrollablePanel();
        view.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, visible.height - 16);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }
}
