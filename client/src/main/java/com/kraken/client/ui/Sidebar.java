package com.kraken.client.ui;

import com.google.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import java.awt.*;

import static com.formdev.flatlaf.FlatClientProperties.*;

/** RuneLite-style sidebar: a strip of tabs on the far right that open one panel at a time. */
@Singleton
public final class Sidebar extends JPanel {
    static final int PANEL_WIDTH = 242;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel tabs = new JPanel();
    /** Widen the window when a panel opens, instead of shrinking the game. */
    @Getter
    @Setter
    private boolean growWindow = true;

    Sidebar() {
        super(new BorderLayout());
        cards.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
        cards.setVisible(false);

        tabs.setLayout(new BoxLayout(tabs, BoxLayout.Y_AXIS));
        tabs.setOpaque(false);

        JPanel strip = new JPanel(new BorderLayout());
        strip.setBackground(Theme.DARKER);
        strip.add(tabs, BorderLayout.NORTH);

        add(cards, BorderLayout.CENTER);
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

    private void select(JToggleButton selected) {
        boolean wasOpen = cards.isVisible();
        for (Component tab : tabs.getComponents()) {
            ((JToggleButton) tab).setSelected(tab == selected);
        }
        if (selected != null) {
            cardLayout.show(cards, selected.getName());
        }
        cards.setVisible(selected != null);

        // Like RuneLite, grow the window for the panel instead of squashing the game
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        if (growWindow && wasOpen != cards.isVisible() && frame.getExtendedState() == Frame.NORMAL) {
            frame.setSize(frame.getWidth() + (cards.isVisible() ? PANEL_WIDTH : -PANEL_WIDTH), frame.getHeight());
        }
        revalidate();
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
}
