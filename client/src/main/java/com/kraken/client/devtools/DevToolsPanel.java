package com.kraken.client.devtools;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.KrakenApi;
import com.kraken.api.game.ClientState;
import com.kraken.api.game.MapCoord;
import com.kraken.api.game.Player;
import com.kraken.client.ApiConnection;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.WrapLabel;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The "Dev Tools" sidebar page: connection status, a few live facts, and every dev tool grouped into collapsible
 * sections with a filter. Each tool has an eye toggle (draw it over the game) and a window button (open its popout);
 * the {@link DevToolManager} does the work.
 */
@Singleton
public final class DevToolsPanel extends JPanel {
    private static final String SHOW_PLANNED = "panel.showPlanned";

    private final DevToolManager manager;
    private final ApiConnection connection;
    private final WrapLabel status = new WrapLabel("");
    private final JLabel gameValue = fact();
    private final JLabel playerValue = fact();
    private final JLabel tileValue = fact();
    private final JLabel tickValue = fact();
    private final JButton allOff = new JButton(FontIcon.of(MaterialDesignE.EYE_OFF_OUTLINE, 18, Theme.LIGHT));
    private final JTextField search = new JTextField();
    private final JToggleButton showPlanned = new JToggleButton(FontIcon.of(MaterialDesignC.CLOCK_OUTLINE, 16, Theme.LIGHT));
    private final List<CategorySection> sections = new ArrayList<>();
    private final JLabel noMatches = new JLabel("", SwingConstants.CENTER);
    private volatile boolean showing;

    @Inject
    DevToolsPanel(DevToolManager manager, ApiConnection connection) {
        super(new BorderLayout());
        this.manager = manager;
        this.connection = connection;
        setBorder(new EmptyBorder(12, 12, 0, 12));
        add(header(), BorderLayout.NORTH);
        add(toolList(), BorderLayout.CENTER);

        manager.addListener(this::refreshState);
        connection.addStateListener(this::showStatus);
        connection.addPollListener(this::readFacts);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                showing = isShowing();
            }
        });
        showStatus();
        applyFilter();
        refreshState();
    }

    private JComponent header() {
        JLabel title = new JLabel("Dev Tools", FontIcon.of(MaterialDesignC.CODE_TAGS, 18, Theme.ACCENT), SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "h3");
        allOff.setDisabledIcon(FontIcon.of(MaterialDesignE.EYE_OFF_OUTLINE, 18, Theme.MEDIUM));
        allOff.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        allOff.setToolTipText("Turn off every overlay and close every tool window");
        allOff.setFocusable(false);
        allOff.addActionListener(e -> manager.turnAllOff());
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(allOff, BorderLayout.EAST);

        status.putClientProperty(STYLE_CLASS, "small");
        status.setForeground(Theme.LIGHT);
        status.setIconTextGap(6);
        status.setVerticalTextPosition(SwingConstants.TOP);
        status.setBorder(new EmptyBorder(6, 2, 10, 0));

        JPanel facts = new JPanel(new GridLayout(0, 1, 0, 2));
        facts.putClientProperty(STYLE, "arc: 12; background: " + Theme.hex(Theme.DARKER));
        facts.setBorder(new EmptyBorder(8, 12, 8, 12));
        facts.add(factRow("Game", gameValue));
        facts.add(factRow("Player", playerValue));
        facts.add(factRow("Tile", tileValue));
        facts.add(factRow("Tick", tickValue));

        showPlanned.setSelectedIcon(FontIcon.of(MaterialDesignC.CLOCK_OUTLINE, 16, Theme.ACCENT));
        showPlanned.setSelected(manager.getPrefs().getBoolean(SHOW_PLANNED, true));
        showPlanned.setToolTipText("Show planned tools");
        showPlanned.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        showPlanned.putClientProperty(STYLE, "toolbar.selectedBackground: $TextField.background");
        showPlanned.setFocusable(false);
        showPlanned.addActionListener(e -> {
            manager.getPrefs().putBoolean(SHOW_PLANNED, showPlanned.isSelected());
            applyFilter();
        });
        search.putClientProperty(PLACEHOLDER_TEXT, "Filter tools");
        search.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        search.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.putClientProperty(TEXT_FIELD_TRAILING_COMPONENT, showPlanned);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setBorder(new EmptyBorder(12, 0, 2, 0));
        searchRow.add(search);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{titleRow, status, facts, searchRow}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            north.add(c);
        }
        return north;
    }

    private JComponent toolList() {
        Map<Category, List<DevTool<?>>> byCategory = new EnumMap<>(Category.class);
        for (DevTool<?> tool : manager.getTools()) {
            byCategory.computeIfAbsent(tool.getCategory(), c -> new ArrayList<>()).add(tool);
        }
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        byCategory.forEach((category, tools) -> {
            CategorySection section = new CategorySection(category, tools, manager);
            sections.add(section);
            content.add(section);
        });
        noMatches.putClientProperty(STYLE_CLASS, "small");
        noMatches.setForeground(Theme.LIGHT);
        noMatches.setBorder(new EmptyBorder(24, 0, 0, 0));
        noMatches.setAlignmentX(LEFT_ALIGNMENT);
        noMatches.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        content.add(noMatches);

        ScrollablePanel view = new ScrollablePanel();
        view.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void applyFilter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (CategorySection section : sections) {
            shown += section.filter(query, showPlanned.isSelected());
        }
        noMatches.setText(query.isEmpty() ? "No tools to show" : "No tools match “" + query + "”");
        noMatches.setVisible(shown == 0);
        revalidate();
        repaint();
    }

    private void refreshState() {
        sections.forEach(CategorySection::refreshState);
        allOff.setEnabled(manager.anyActive());
    }

    private void showStatus() {
        Color dot = switch (connection.getState()) {
            case ATTACHED -> Theme.ACCENT;
            case ATTACHING -> Theme.WARNING;
            case UNSUPPORTED, FAILED -> Theme.ERROR;
            case WAITING -> Theme.MEDIUM;
        };
        status.setIcon(new Dot(dot));
        status.setPlainText(connection.getDetail());
        if (!connection.isAttached()) {
            for (JLabel value : new JLabel[]{gameValue, playerValue, tileValue, tickValue}) {
                value.setText("–");
            }
        }
    }

    /**
     * Poll thread: reads the facts card while this page is on screen.
     *
     * @param api the attached API
     */
    private void readFacts(KrakenApi api) {
        if (!showing) {
            return;
        }
        ClientState clientState = api.clientState();
        int state = clientState.getGameState();
        int tick = clientState.getTickCount();
        Player local = api.client().getLocalPlayer();
        MapCoord at = local == null ? null : local.getCoord();
        String game = clientState.isLoggedIn() ? "Logged in" : "Not logged in (" + state + ")";
        String name = local == null ? "" : local.getName();
        String player = name.isEmpty() ? "–" : name;
        String tile = at == null ? "–" : at.x() + ", " + at.z() + ", " + at.level();
        SwingUtilities.invokeLater(() -> {
            gameValue.setText(game);
            playerValue.setText(player);
            tileValue.setText(tile);
            tickValue.setText(String.valueOf(tick));
        });
    }

    private static JLabel fact() {
        JLabel label = new JLabel("–");
        label.setForeground(Theme.TEXT);
        return label;
    }

    private static JComponent factRow(String key, JLabel value) {
        JLabel label = new JLabel(key);
        label.setForeground(Theme.LIGHT);
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /**
     * A status light, as tall as a line of small text so it lines up with the first line.
     */
    private record Dot(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y + (getIconHeight() - 8) / 2, 8, 8);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 15;
        }
    }

    /**
     * Scroll view that always fits the sidebar's width, so rows wrap instead of scrolling sideways.
     */
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel() {
            super(new BorderLayout());
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
}
