package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.ExitHandler;
import com.kraken.client.GameView;
import com.kraken.client.HomePanel;
import com.kraken.client.config.ConfigManager;
import com.kraken.client.config.KrakenConfig;
import com.kraken.client.devtools.DevToolsPanel;
import com.kraken.client.events.ConfigChanged;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE;

/**
 * RuneLite-style main window: the game with the sidebar on its right, and the log console docked underneath. Closing
 * it closes Kraken through the {@link ExitHandler}. The Settings page is built from {@link KrakenConfig}, and the
 * window follows its settings as they change.
 */
@Singleton
public final class KrakenFrame extends JFrame {

    /**
     * The {@link KrakenConfig} keys the window applies itself.
     */
    private static final Set<String> WINDOW_KEYS = Set.of("alwaysOnTop", "growWindow");

    private final Sidebar sidebar;
    private final KrakenConfig config;

    @Inject
    KrakenFrame(GameView view, HomePanel home, Sidebar sidebar, ConsolePanel console, DevToolsPanel devTools,
                PluginListPanel plugins, ExitHandler exitHandler, ConfigManager configManager, EventBus eventBus,
                KrakenConfig config) {
        super("Kraken");
        this.sidebar = sidebar;
        this.config = config;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
                exitHandler.exit();
            }
        });
        setIconImages(Stream.of(16, 20, 24, 30, 32, 40, 48, 64, 128, 256).map(Theme::logo).toList());
        getRootPane().putClientProperty(TITLE_BAR_SHOW_TITLE, false);

        Logger.getLogger("").addHandler(console.getHandler());

        home.set("PID", "–");
        home.set("Build", "–");
        home.set("Game size", "–");
        view.addPropertyChangeListener("gameSize", e -> {
            Dimension size = (Dimension) e.getNewValue();
            home.set("Game size", size.width + " × " + size.height);
        });

        sidebar.addTab("Kraken", Theme.logoIcon(), Theme.logoIcon(), home);
        sidebar.addTab("Dev Tools", MaterialDesignC.CODE_TAGS, devTools);
        sidebar.addTab("Plugins", MaterialDesignP.PUZZLE_OUTLINE, plugins);
        sidebar.addTab("Settings", MaterialDesignC.COG_OUTLINE,
                new ConfigPanel(configManager, eventBus, config, "Settings", null));
        applySettings();
        eventBus.register(ConfigChanged.class, event -> {
            if (KrakenConfig.GROUP.equals(event.getGroup()) && WINDOW_KEYS.contains(event.getKey())) {
                SwingUtilities.invokeLater(this::applySettings);
            }
        }, 0);

        JPanel main = new JPanel(new BorderLayout());
        main.add(view, BorderLayout.CENTER);
        main.add(sidebar, BorderLayout.EAST);
        add(main, BorderLayout.CENTER);
        add(console, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(480, 360));
        setLocationRelativeTo(null);
    }

    /**
     * Makes the window follow the window settings.
     */
    private void applySettings() {
        setAlwaysOnTop(config.alwaysOnTop());
        sidebar.setGrowWindow(config.growWindow());
    }
}
