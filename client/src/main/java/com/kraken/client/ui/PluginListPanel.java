package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.PluginManager;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The "Plugins" sidebar page, as RuneLite's plugin list: every plugin that isn't hidden, sorted by name, with a search
 * over names, descriptions and tags, and a switch that turns each on or off through the {@link PluginManager}. A plugin
 * that failed to start shows "failed", with the error in its tooltip. A plugin with settings has a cog, which swaps the
 * page for its {@link ConfigPanel} until the back arrow is clicked.
 */
@Singleton
public final class PluginListPanel extends JPanel {

    private static final String LIST = "list";
    private static final String SETTINGS = "settings";

    private final PluginManager manager;
    private final ConfigManager configManager;
    private final EventBus eventBus;
    private final CardLayout cards = new CardLayout();
    private final JPanel listPage = new JPanel(new BorderLayout());
    private ConfigPanel settingsPage;
    private final JTextField search = new JTextField();
    private final JPanel list = new JPanel();
    private final JLabel noMatches = new JLabel("", SwingConstants.CENTER);
    private final List<Row> rows = new ArrayList<>();

    @Inject
    PluginListPanel(PluginManager manager, ConfigManager configManager, EventBus eventBus) {
        this.manager = manager;
        this.configManager = configManager;
        this.eventBus = eventBus;
        setLayout(cards);
        listPage.setBorder(new EmptyBorder(12, 12, 0, 12));
        listPage.add(header(), BorderLayout.NORTH);
        add(listPage, LIST);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        noMatches.putClientProperty(STYLE_CLASS, "small");
        noMatches.setForeground(Theme.LIGHT);
        noMatches.setBorder(new EmptyBorder(24, 0, 0, 0));
        noMatches.setAlignmentX(LEFT_ALIGNMENT);
        noMatches.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        listPage.add(ScrollablePanel.scroll(list), BorderLayout.CENTER);

        manager.addChangeListener(this::refresh);
        refresh();
    }

    /**
     * Swaps the list for a plugin's settings page.
     *
     * @param plugin the plugin
     * @param config its settings
     */
    private void showSettings(Plugin plugin, Config config) {
        closeSettings();
        settingsPage = new ConfigPanel(configManager, eventBus, config, plugin.getName(), this::showList);
        add(settingsPage, SETTINGS);
        cards.show(this, SETTINGS);
    }

    private void showList() {
        cards.show(this, LIST);
        closeSettings();
    }

    private void closeSettings() {
        if (settingsPage != null) {
            remove(settingsPage);
            settingsPage = null;
        }
    }

    private JComponent header() {
        JLabel title = new JLabel("Plugins", FontIcon.of(MaterialDesignP.PUZZLE_OUTLINE, 18, Theme.ACCENT),
                SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "h3");
        search.putClientProperty(PLACEHOLDER_TEXT, "Search plugins");
        search.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        search.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
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
        searchRow.setBorder(new EmptyBorder(12, 0, 8, 0));
        searchRow.add(search);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{title, searchRow}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            north.add(c);
        }
        return north;
    }

    /**
     * Rebuilds the rows if plugins were loaded, then shows each plugin's state. EDT.
     */
    private void refresh() {
        List<Plugin> shown = manager.getPlugins().stream()
                .filter(plugin -> !plugin.getClass().getAnnotation(PluginDescriptor.class).hidden())
                .sorted(Comparator.comparing(plugin -> plugin.getName().toLowerCase(Locale.ROOT)))
                .toList();
        if (!shown.equals(rows.stream().map(row -> row.plugin).toList())) {
            rows.clear();
            list.removeAll();
            for (Plugin plugin : shown) {
                Row row = new Row(plugin);
                rows.add(row);
                list.add(row);
            }
            list.add(noMatches);
        }
        rows.forEach(Row::update);
        applyFilter();
    }

    private void applyFilter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Row row : rows) {
            boolean match = query.isEmpty() || row.searchText.contains(query);
            row.setVisible(match);
            shown += match ? 1 : 0;
        }
        noMatches.setText(rows.isEmpty() ? "No plugins loaded" : "No plugins match “" + query + "”");
        noMatches.setVisible(shown == 0);
        revalidate();
        repaint();
    }

    /**
     * One plugin: its name, a "failed" marker when it failed to start, and its switch.
     */
    private final class Row extends JPanel {
        private final Plugin plugin;
        private final String description;
        private final String searchText;
        private final JLabel failed = new JLabel("failed", FontIcon.of(MaterialDesignA.ALERT_CIRCLE_OUTLINE, 14,
                Theme.ERROR), SwingConstants.LEFT);
        private final JToggleButton toggle = new JToggleButton(FontIcon.of(MaterialDesignT.TOGGLE_SWITCH_OFF_OUTLINE,
                24, Theme.LIGHT));
        private final JButton settings = new JButton(FontIcon.of(MaterialDesignC.COG_OUTLINE, 18, Theme.LIGHT));

        Row(Plugin plugin) {
            super(new BorderLayout(8, 0));
            this.plugin = plugin;
            PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
            this.description = descriptor.description();
            this.searchText = (plugin.getName() + " " + description + " " + String.join(" ", descriptor.tags()))
                    .toLowerCase(Locale.ROOT);
            setBorder(new EmptyBorder(2, 4, 2, 0));
            setAlignmentX(LEFT_ALIGNMENT);

            JLabel name = new JLabel(plugin.getName());
            failed.putClientProperty(STYLE_CLASS, "small");
            failed.setForeground(Theme.ERROR);
            failed.setIconTextGap(3);
            Box labels = Box.createHorizontalBox();
            labels.add(name);
            labels.add(Box.createHorizontalStrut(8));
            labels.add(failed);

            toggle.setSelectedIcon(FontIcon.of(MaterialDesignT.TOGGLE_SWITCH, 24, Theme.ACCENT));
            toggle.setDisabledIcon(FontIcon.of(MaterialDesignT.TOGGLE_SWITCH_OFF_OUTLINE, 24, Theme.MEDIUM));
            toggle.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
            toggle.setFocusable(false);
            toggle.addActionListener(e -> toggled());

            settings.setRolloverIcon(FontIcon.of(MaterialDesignC.COG_OUTLINE, 18, Theme.TEXT));
            settings.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
            settings.setToolTipText("Settings");
            settings.setFocusable(false);
            settings.addActionListener(e -> {
                Config config = manager.getPluginConfigProxy(plugin);
                if (config != null) {
                    showSettings(plugin, config);
                }
            });
            settings.setVisible(manager.getPluginConfigProxy(plugin) != null);

            Box buttons = Box.createHorizontalBox();
            buttons.add(settings);
            buttons.add(toggle);
            add(labels, BorderLayout.CENTER);
            add(buttons, BorderLayout.EAST);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /**
         * Shows whether the plugin is on and whether it failed.
         */
        void update() {
            boolean on = manager.isPluginEnabled(plugin);
            boolean loadable = plugin.getInjector() != null;
            toggle.setSelected(on && loadable);
            toggle.setEnabled(loadable);
            toggle.setToolTipText(toggle.isSelected() ? "Turn off" : "Turn on");
            Throwable failure = manager.getFailure(plugin);
            failed.setVisible(failure != null);
            String tip = "<html>" + Theme.escape(description.isEmpty() ? plugin.getName() : description);
            if (failure != null) {
                tip += "<br><font color='" + Theme.hex(Theme.ERROR) + "'>" + (loadable ? "Failed to start: "
                        : "Failed to load: ") + Theme.escape(message(failure)) + "</font><br>See the console.";
            }
            setToolTipText(tip + "</html>");
        }

        private void toggled() {
            if (toggle.isSelected()) {
                List<Plugin> conflicts = manager.conflictsForPlugin(plugin).stream()
                        .filter(manager::isPluginEnabled).toList();
                if (!conflicts.isEmpty() && !confirmConflicts(conflicts)) {
                    toggle.setSelected(false);
                    return;
                }
                manager.setPluginEnabled(plugin, true);
                manager.startPlugin(plugin);
            } else {
                manager.setPluginEnabled(plugin, false);
                manager.stopPlugin(plugin);
            }
            update();
        }

        private boolean confirmConflicts(List<Plugin> conflicts) {
            String names = conflicts.stream().map(Plugin::getName).collect(Collectors.joining(", "));
            int answer = JOptionPane.showConfirmDialog(this, plugin.getName() + " can't be on with " + names
                            + ".\nTurn " + (conflicts.size() == 1 ? "it" : "them") + " off?", "Plugin conflict",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            return answer == JOptionPane.OK_OPTION;
        }

        private static String message(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getMessage() == null) {
                cause = cause.getCause();
            }
            return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        }
    }
}
