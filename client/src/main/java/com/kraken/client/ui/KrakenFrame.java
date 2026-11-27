package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.actor.Player;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.ExitConfirmation;
import com.kraken.client.ExitHandler;
import com.kraken.client.GameView;
import com.kraken.client.KrakenConfig;
import com.kraken.client.ProcessAttacher;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.devtools.DevToolsPanel;
import com.kraken.client.worldhopper.WorldHopperPanel;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.swing.*;

import static com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE;

/**
 * The main window: the game with the sidebar on its right, and the log console docked underneath. Closing it closes
 * Kraken through the {@link ExitHandler}, after asking first if {@link KrakenConfig#confirmExit} says to. The Settings
 * page is built from {@link KrakenConfig}, and the window follows its settings as they change.
 *
 * <p>The window opens around the game at {@link KrakenConfig#gameSize}, with the console and the sidebar panel as they
 * were left: centred, or, with {@link KrakenConfig#rememberWindow} on, where it was last left, maximised if it was. A
 * saved position that no screen shows the top of is ignored. The window's state is saved a second after it stops moving
 * or resizing, and as it closes. Sizes in the settings are the game's own pixels, so on a scaled display the window is
 * sized by the display's scale.
 */
@Singleton
public final class KrakenFrame extends JFrame {

    /**
     * The {@link KrakenConfig} keys the window applies itself, besides {@code gameSize}.
     */
    private static final Set<String> WINDOW_KEYS = Set.of("alwaysOnTop", "growWindow", "lockWindowSize",
            "playerNameInTitle");

    private static final String TITLE = "Kraken";
    private static final Dimension MIN_SIZE = new Dimension(480, 360);

    /**
     * The bounds on the game size, whatever the settings file holds.
     */
    private static final int MIN_GAME_SIDE = 240;
    private static final int MAX_GAME_SIDE = 7680;

    /**
     * How long the window has to stay still before its state is saved.
     */
    private static final int SAVE_DELAY_MS = 1000;

    /**
     * How much of a saved window's top edge a screen must show for its bounds to be used: enough to grab it by.
     */
    private static final int VISIBLE_TOP_WIDTH = 100;
    private static final int VISIBLE_TOP_HEIGHT = 20;

    private final GameView view;
    private final Sidebar sidebar;
    private final KrakenConfig config;
    private final ProcessAttacher attacher;
    private final Timer saveTimer = new Timer(SAVE_DELAY_MS, e -> saveWindow());

    /**
     * Whether a player is logged in, and the name the title shows; read on the API poll thread.
     */
    private volatile boolean loggedIn;
    private volatile String playerName;

    @Inject
    KrakenFrame(GameView view, HomePanel home, Sidebar sidebar, ConsolePanel console, DevToolsPanel devTools,
                PluginListPanel plugins, WorldHopperPanel worldHopper, ExitHandler exitHandler,
                ConfigManager configManager, EventBus eventBus, KrakenConfig config, ProcessAttacher attacher) {
        super(TITLE);
        this.view = view;
        this.sidebar = sidebar;
        this.config = config;
        this.attacher = attacher;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!confirmClose()) {
                    return;
                }
                saveTimer.stop();
                saveWindow();
                setVisible(false);
                exitHandler.exit();
            }
        });
        setIconImages(Stream.of(16, 20, 24, 30, 32, 40, 48, 64, 128, 256).map(Theme::logo).toList());
        getRootPane().putClientProperty(TITLE_BAR_SHOW_TITLE, false);

        Logger.getLogger("").addHandler(console.getHandler());

        sidebar.addTab("Kraken", Theme.logoIcon(), Theme.logoIcon(), home);
        sidebar.addTab("Plugins", MaterialDesignP.PUZZLE_OUTLINE, plugins);
        sidebar.addTab("World Hopper", MaterialDesignE.EARTH, worldHopper);
        sidebar.addTab("Dev Tools", MaterialDesignC.CODE_TAGS, devTools);
        sidebar.addTab("Settings", MaterialDesignC.COG_OUTLINE,
                new ConfigPanel(configManager, eventBus, config, "Settings", null));
        applySettings();
        eventBus.register(ConfigChanged.class, event -> {
            if (!KrakenConfig.GROUP.equals(event.getGroup())) {
                return;
            }
            if (WINDOW_KEYS.contains(event.getKey())) {
                SwingUtilities.invokeLater(this::applySettings);
            } else if ("gameSize".equals(event.getKey())) {
                SwingUtilities.invokeLater(this::applyGameSize);
            }
        }, 0);

        JPanel main = new JPanel(new BorderLayout());
        main.add(view, BorderLayout.CENTER);
        main.add(sidebar, BorderLayout.EAST);
        add(main, BorderLayout.CENTER);
        add(console, BorderLayout.SOUTH);
        setMinimumSize(MIN_SIZE);
        place();

        saveTimer.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                saveTimer.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                saveTimer.restart();
            }
        });
        addWindowStateListener(e -> saveTimer.restart());
        attacher.addPollListener(this::readPlayer);
    }

    /**
     * Sizes the window around the game at the game size, with the sidebar panel that was open, and places it: where
     * it was last left, maximised if it was, if that's wanted and a screen shows the top of its saved bounds,
     * otherwise centred. Only the saved position is used, so the game opens at the game size even if the console or
     * the sidebar changed size since the bounds were saved.
     */
    private void place() {
        Rectangle saved = config.rememberWindow() ? config.windowBounds() : null;
        GraphicsConfiguration screen = saved != null ? screenShowingTop(saved) : null;
        sidebar.restore();
        view.setPreferredSize(gameSize(screen != null ? screen : getGraphicsConfiguration()));
        // The sidebar's and the console's preferred sizes are their restored sizes, so packing gives the game the rest
        pack();
        if (screen == null) {
            setLocationRelativeTo(null);
            keepOnScreen(getGraphicsConfiguration());
            return;
        }
        setLocation(saved.x, saved.y);
        keepOnScreen(screen);
        if (config.windowMaximized()) {
            setExtendedState(MAXIMIZED_BOTH);
        }
    }

    /**
     * Resizes the window so the game is the size the settings give, keeping the sidebar and console as they are.
     */
    private void applyGameSize() {
        if ((getExtendedState() & MAXIMIZED_BOTH) != 0) {
            setExtendedState(NORMAL);
        }
        view.setPreferredSize(gameSize(getGraphicsConfiguration()));
        // The sidebar's and the console's preferred sizes are their sizes now, so packing changes only the game's
        pack();
        keepOnScreen(getGraphicsConfiguration());
    }

    /**
     * The game size from the settings, in a screen's window pixels.
     *
     * @param screen the screen the window is on, or is about to be placed on
     * @return the size the game view should be
     */
    private Dimension gameSize(GraphicsConfiguration screen) {
        Dimension size = config.gameSize();
        if (size == null) {
            size = new Dimension(765, 503);
        }
        double scale = screen.getDefaultTransform().getScaleX();
        int width = Math.max(MIN_GAME_SIDE, Math.min(MAX_GAME_SIDE, size.width));
        int height = Math.max(MIN_GAME_SIDE, Math.min(MAX_GAME_SIDE, size.height));
        return new Dimension((int) Math.round(width / scale), (int) Math.round(height / scale));
    }

    /**
     * Moves the window up and left as far as needed to fit on a screen, when it fits at all.
     *
     * @param screen the screen the window is on, or is being placed on
     */
    private void keepOnScreen(GraphicsConfiguration screen) {
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        Rectangle area = screen.getBounds();
        int left = area.x + insets.left;
        int top = area.y + insets.top;
        int right = area.x + area.width - insets.right;
        int bottom = area.y + area.height - insets.bottom;
        Rectangle bounds = getBounds();
        int x = Math.max(left, Math.min(bounds.x, right - bounds.width));
        int y = Math.max(top, Math.min(bounds.y, bottom - bounds.height));
        if (x != bounds.x || y != bounds.y) {
            setLocation(x, y);
        }
    }

    /**
     * Finds a screen that shows enough of a window's top edge to grab it by.
     *
     * @param bounds the window's bounds
     * @return the first such screen, or null if none does
     */
    private static GraphicsConfiguration screenShowingTop(Rectangle bounds) {
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        Rectangle top = new Rectangle(bounds.x, bounds.y, bounds.width, VISIBLE_TOP_HEIGHT);
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration screen = device.getDefaultConfiguration();
            Rectangle shown = screen.getBounds().intersection(top);
            if (shown.width >= Math.min(VISIBLE_TOP_WIDTH, bounds.width) && shown.height >= VISIBLE_TOP_HEIGHT) {
                return screen;
            }
        }
        return null;
    }

    /**
     * Saves the window's state for the next launch: whether it's maximised and, when it isn't, its bounds. Nothing is
     * saved while it's minimised or hidden.
     */
    private void saveWindow() {
        int state = getExtendedState();
        if (!isShowing() || (state & ICONIFIED) != 0) {
            return;
        }
        boolean maximized = (state & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        config.windowMaximized(maximized);
        if (!maximized) {
            config.windowBounds(getBounds());
        }
    }

    /**
     * Asks whether to close, if the settings say to.
     *
     * @return true to close
     */
    private boolean confirmClose() {
        ExitConfirmation when = config.confirmExit();
        boolean playing = loggedIn && attacher.isAttached();
        if (when == ExitConfirmation.NEVER || when == ExitConfirmation.LOGGED_IN && !playing) {
            return true;
        }
        String message = playing ? "You're logged in. Closing Kraken closes the game too."
                : "Closing Kraken closes the game too.";
        Object[] options = {"Close", "Cancel"};
        int answer = JOptionPane.showOptionDialog(this, message, "Close Kraken?", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return answer == 0;
    }

    /**
     * Notes whether a player is logged in, and their name if the title shows it. API poll thread.
     *
     * @param session the attached API
     */
    private void readPlayer(GameSession session) {
        boolean in = false;
        String name = null;
        try {
            Client client = session.client();
            in = client.isLoggedIn();
            if (in && config.playerNameInTitle()) {
                Player player = client.getLocalPlayer();
                String read = player != null ? player.getName() : null;
                name = read == null || read.isBlank() ? null : read;
            }
        } catch (RuntimeException e) {
            // Unreadable mid-login or as the game exits: the title shows no name until the next poll reads one
        }
        loggedIn = in;
        if (!Objects.equals(name, playerName)) {
            playerName = name;
            SwingUtilities.invokeLater(this::updateTitle);
        }
    }

    private void updateTitle() {
        String name = playerName;
        setTitle(config.playerNameInTitle() && name != null ? TITLE + " - " + name : TITLE);
    }

    /**
     * Makes the window follow the window settings.
     */
    private void applySettings() {
        setAlwaysOnTop(config.alwaysOnTop());
        setResizable(!config.lockWindowSize());
        sidebar.setGrowWindow(config.growWindow());
        updateTitle();
    }
}
