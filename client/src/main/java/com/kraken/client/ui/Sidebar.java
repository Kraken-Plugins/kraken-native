package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.KrakenConfig;
import lombok.Getter;
import lombok.Setter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The sidebar is a strip of tabs on the far right that open one panel at a time. Drag the open panel's left edge to
 * resize it: the game gives up or gets back the width, and every panel shares the width. Double-click the edge to
 * reset it. The width and which panel is open are kept in {@link KrakenConfig}'s hidden items, and the frame reopens
 * the panel at launch ({@link #restore}).
 */
@Singleton
public final class Sidebar extends JPanel {
    /** The narrowest a panel can be, and its starting width: the width panels are designed for. */
    private static final int MIN_WIDTH = 242;
    /** Dragging the panel wider stops when the game would get narrower than this. */
    private static final int MIN_GAME_WIDTH = 320;
    private static final int GRIP_WIDTH = 5;

    private final KrakenConfig config;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel drawer = new JPanel(new BorderLayout());
    private final JPanel tabs = new JPanel();
    private final JPanel strip = new JPanel(new BorderLayout());
    private int panelWidth;
    /**
     * How much the window grew when the panel opened, given back when it closes.
     */
    private int grownBy;
    /** Widen the window when a panel opens, instead of shrinking the game. */
    @Getter
    @Setter
    private boolean growWindow = true;

    @Inject
    Sidebar(KrakenConfig config) {
        super(new BorderLayout());
        this.config = config;
        panelWidth = Math.max(MIN_WIDTH, config.sidebarWidth());
        drawer.add(new Grip(), BorderLayout.WEST);
        drawer.add(cards, BorderLayout.CENTER);
        drawer.setPreferredSize(new Dimension(GRIP_WIDTH + panelWidth, 0));
        drawer.setVisible(false);

        tabs.setLayout(new BoxLayout(tabs, BoxLayout.Y_AXIS));
        tabs.setOpaque(false);
        strip.setBackground(Theme.DARKER);
        strip.add(tabs, BorderLayout.NORTH);

        add(drawer, BorderLayout.CENTER);
        add(strip, BorderLayout.EAST);
    }

    void addTab(String title, Ikon ikon, JComponent content) {
        addTab(title, FontIcon.of(ikon, 20, Theme.LIGHT), FontIcon.of(ikon, 20, Theme.ACCENT), content);
    }

    /** Adds a tab that opens {@code content}; clicking the open tab again closes the panel. */
    void addTab(String title, Icon icon, Icon selectedIcon, JComponent content) {
        JToggleButton tab = button(title, icon, selectedIcon);
        tab.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TAB);
        tab.putClientProperty(TAB_BUTTON_UNDERLINE_PLACEMENT, SwingConstants.LEFT);
        tab.addActionListener(e -> select(tab.isSelected() ? tab : null));
        tabs.add(tab);
        cards.add(content, title);
    }

    /**
     * Reopens the panel that was open when Kraken last closed, if any, without touching the window's size: the frame
     * sizes the window around it afterwards. Closing it gives its width back if {@link #growWindow} is on, as if
     * opening it had widened the window. With it off, the game gets the width back instead.
     */
    void restore() {
        String title = config.sidebarPanel();
        for (Component component : tabs.getComponents()) {
            if (component instanceof JToggleButton tab && tab.getName().equals(title)) {
                grownBy = growWindow ? GRIP_WIDTH + panelWidth : 0;
                showPanel(tab);
                return;
            }
        }
    }

    private void select(JToggleButton selected) {
        boolean wasOpen = drawer.isVisible();
        boolean open = selected != null;
        showPanel(selected);

        // Grow the window for the panel instead of squashing the game, and give it back on close
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        boolean normal = frame.getExtendedState() == Frame.NORMAL;
        if (open && !wasOpen && growWindow && normal) {
            grownBy = GRIP_WIDTH + panelWidth;
            frame.setSize(frame.getWidth() + grownBy, frame.getHeight());
        } else if (!open && wasOpen) {
            if (normal && grownBy > 0) {
                frame.setSize(frame.getWidth() - grownBy, frame.getHeight());
            }
            grownBy = 0;
        }
        revalidate();
    }

    /**
     * Shows a tab's panel, or none, without touching the window's size, and remembers which.
     *
     * @param selected the tab, or null to close the panel
     */
    private void showPanel(JToggleButton selected) {
        for (Component tab : tabs.getComponents()) {
            ((JToggleButton) tab).setSelected(tab == selected);
        }
        if (selected != null) {
            cardLayout.show(cards, selected.getName());
        }
        drawer.setVisible(selected != null);
        revalidate();
        config.sidebarPanel(selected != null ? selected.getName() : "");
    }

    /** Resizes the open panel, taking the width from the game (which keeps at least {@link #MIN_GAME_WIDTH}). */
    private void setPanelWidth(int width) {
        int max = getParent().getWidth() - strip.getWidth() - GRIP_WIDTH - MIN_GAME_WIDTH;
        int clamped = Math.max(MIN_WIDTH, Math.min(width, max));
        if (clamped != panelWidth) {
            panelWidth = clamped;
            drawer.setPreferredSize(new Dimension(GRIP_WIDTH + panelWidth, 0));
            revalidate();
        }
    }

    private static JToggleButton button(String title, Icon icon, Icon selectedIcon) {
        JToggleButton button = new JToggleButton(icon);
        button.setSelectedIcon(selectedIcon);
        button.setName(title);
        button.setToolTipText(title);
        button.setFocusable(false);
        // As wide as the title bar buttons, so the strip lines up under the close button
        Dimension size = new Dimension(UIManager.getDimension("TitlePane.buttonSize").width, 40);
        button.setPreferredSize(size);
        button.setMaximumSize(size);
        return button;
    }

    /** The panel's left edge: drag to resize, double-click to reset. Lights up while hovered or dragged. */
    private final class Grip extends JComponent {
        private boolean hovered;
        private boolean dragging;

        Grip() {
            setPreferredSize(new Dimension(GRIP_WIDTH, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                private int startX;
                private int startWidth;

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    startX = e.getXOnScreen();
                    startWidth = panelWidth;
                    dragging = true;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    setPanelWidth(startWidth + startX - e.getXOnScreen());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = false;
                    repaint();
                    config.sidebarWidth(panelWidth);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        setPanelWidth(MIN_WIDTH);
                        config.sidebarWidth(panelWidth);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hovered || dragging) {
                g.setColor(Theme.ACCENT);
                g.fillRect(getWidth() / 2 - 1, 0, 2, getHeight());
            }
        }
    }
}
