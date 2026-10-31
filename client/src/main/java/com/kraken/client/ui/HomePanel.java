package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.ClientBuild;
import com.kraken.api.GameSession;
import com.kraken.client.FrameRates;
import com.kraken.client.GameView;
import com.kraken.client.KrakenProperties;
import com.kraken.client.ProcessAttacher;
import com.kraken.client.callback.NativeEventServer;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.PluginManager;
import lombok.extern.java.Log;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import static com.formdev.flatlaf.FlatClientProperties.STYLE;
import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;

/**
 * The "Kraken" sidebar page: the attach status, cards of facts about Kraken, the game client, the game and the API,
 * and links to report an issue, the website, the guides and the settings folder.
 *
 * <p>Each fact comes from where it's kept: Kraken's version and commit from {@link KrakenProperties}, the game's
 * process, build and mapping from the {@link ProcessAttacher}, the frame rates from {@link FrameRates}, the hook's
 * state from the session's {@link NativeEventServer}, and the game's state from {@link GameFacts}, read on the API
 * poll thread while the page is showing. The frame rates, the process and the hook are refreshed once a second. All
 * of it is shown on the EDT.
 */
@Log(topic = "kraken")
@Singleton
public final class HomePanel extends JPanel {
    private static final String UNKNOWN = "–";
    private static final int REFRESH_MS = 1000;

    /**
     * How much of the build's SHA-256 the Build row shows: enough to tell builds with the same version apart.
     */
    private static final int BUILD_CHARS = 16;

    private final ProcessAttacher attacher;
    private final PluginManager plugins;
    private final FrameRates frameRates;
    private final Executor background;
    private final WrapLabel status = new WrapLabel("");

    private final WrapLabel pluginCount;
    private final WrapLabel overlayFps;
    private final WrapLabel clientVersion;
    private final WrapLabel build;
    private final WrapLabel pid;
    private final WrapLabel gameSize;
    private final WrapLabel gameFps;
    private final WrapLabel state;
    private final WrapLabel player;
    private final WrapLabel world;
    private final WrapLabel worldType;
    private final WrapLabel mapping;
    private final WrapLabel hookState;
    private final WrapLabel hooks;
    private final WrapLabel features;

    /**
     * The session's hook server, once the API attaches.
     */
    private volatile NativeEventServer hook;

    /**
     * Whether the page is on screen. The game is only read while it is.
     */
    private volatile boolean showing;

    /**
     * The game facts last read. Written on the API poll thread.
     */
    private volatile GameFacts game = GameFacts.NONE;

    /**
     * Builds the page and starts following the game.
     *
     * @param attacher   the attach state, and the game's process, build and mapping
     * @param plugins    the plugins, for how many are on
     * @param frameRates the game's and the overlays' frame rates
     * @param view       the game view, for the game's size
     * @param background where links are opened, off the EDT
     * @param settings   the settings file, whose folder the last link opens
     */
    @Inject
    HomePanel(ProcessAttacher attacher, PluginManager plugins, FrameRates frameRates, GameView view,
              ScheduledExecutorService background, @Named("settings") Path settings) {
        super(new BorderLayout());
        this.attacher = attacher;
        this.plugins = plugins;
        this.frameRates = frameRates;
        this.background = background;

        Card kraken = new Card();
        kraken.row("Version", text(KrakenProperties.getVersion()));
        kraken.row("Commit", text(KrakenProperties.getCommit()));
        kraken.row("Java", Runtime.version().toString())
                .setToolTipText(System.getProperty("java.vm.name") + " (" + System.getProperty("java.vendor") + ")");
        pluginCount = kraken.row("Plugins", UNKNOWN);
        overlayFps = kraken.row("Overlay FPS", UNKNOWN);

        Card client = new Card();
        clientVersion = client.row("Version", UNKNOWN);
        build = client.row("Build", UNKNOWN);
        pid = client.row("PID", UNKNOWN);
        gameSize = client.row("Size", UNKNOWN);
        gameFps = client.row("FPS", UNKNOWN);

        Card gameCard = new Card();
        state = gameCard.row("State", UNKNOWN);
        player = gameCard.row("Player", UNKNOWN);
        world = gameCard.row("World", UNKNOWN);
        worldType = gameCard.row("World type", UNKNOWN);
        String account = GameFacts.jagexAccount(System.getenv());
        WrapLabel jagex = gameCard.row("Jagex account", account != null ? account : "not set");
        if (account == null) {
            jagex.setToolTipText("Set JX_SESSION_ID, JX_CHARACTER_ID and JX_DISPLAY_NAME before starting Kraken to "
                    + "log in with a Jagex account");
        }

        Card api = new Card();
        mapping = api.row("Mapping", UNKNOWN);
        hookState = api.row("Native hook", UNKNOWN);
        hooks = api.row("Hooks", UNKNOWN);
        features = api.row("Features", UNKNOWN);

        JLabel title = new JLabel("Kraken", new ImageIcon(Theme.logoImage(18)), SwingConstants.LEFT);
        title.putClientProperty(STYLE_CLASS, "h3");
        status.putClientProperty(STYLE_CLASS, "small");
        status.setForeground(Theme.LIGHT);
        status.setIconTextGap(6);
        status.setVerticalTextPosition(SwingConstants.TOP);
        status.setBorder(new EmptyBorder(8, 2, 0, 0));

        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(new EmptyBorder(12, 12, 12, 12));
        for (JComponent c : new JComponent[]{title, status, heading("Kraken"), kraken, heading("Game client"), client,
                heading("Game"), gameCard, heading("API"), api, links(settings)}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            page.add(c);
        }
        add(ScrollablePanel.scroll(page), BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                showing = isShowing();
            }
        });
        attacher.addStateListener(() -> {
            showStatus();
            showClient();
        });
        attacher.addAttachListener(injector -> hook = injector.getInstance(NativeEventServer.class));
        attacher.addPollListener(this::readGame);
        plugins.addChangeListener(this::showPlugins);
        view.addPropertyChangeListener("gameSize", e -> {
            Dimension size = (Dimension) e.getNewValue();
            SwingUtilities.invokeLater(() -> gameSize.setPlainText(size.width + " × " + size.height));
        });
        new Timer(REFRESH_MS, e -> refresh()).start();
        showStatus();
        showPlugins();
        refresh();
    }

    /**
     * The links under the cards, in the order: report an issue, the website, the guides, the settings folder. A link
     * {@link KrakenProperties} has no address for is left out.
     *
     * @param settings the settings file
     * @return the links
     */
    private JComponent links(Path settings) {
        List<JComponent> shown = new ArrayList<>();
        String issues = KrakenProperties.getIssuesLink();
        if (issues != null) {
            shown.add(link(MaterialDesignG.GITHUB, "Report an issue", "or make a suggestion on GitHub", issues,
                    () -> browse(issues)));
        }
        String website = KrakenProperties.getWebsiteLink();
        if (website != null) {
            shown.add(link(MaterialDesignW.WEB, "Visit the website", URI.create(website).getHost(), website,
                    () -> browse(website)));
        }
        String docs = KrakenProperties.getDocsLink();
        if (docs != null) {
            shown.add(link(MaterialDesignB.BOOK_OPEN_VARIANT, "Read the guides", "to the client, its plugins and the API",
                    docs, () -> browse(docs)));
        }
        Path folder = settings.toAbsolutePath().getParent();
        shown.add(link(MaterialDesignF.FOLDER_OUTLINE, "Open the settings folder", shortPath(folder), folder.toString(),
                () -> open(folder)));

        JPanel links = new JPanel();
        links.setLayout(new BoxLayout(links, BoxLayout.Y_AXIS));
        for (JComponent link : shown) {
            JComponent gap = (JComponent) Box.createVerticalStrut(links.getComponentCount() == 0 ? 16 : 8);
            gap.setAlignmentX(LEFT_ALIGNMENT);
            link.setAlignmentX(LEFT_ALIGNMENT);
            links.add(gap);
            links.add(link);
        }
        return links;
    }

    /**
     * Updates what changes without telling the page: the frame rates, the game's process and build, and the hook.
     * EDT, once a second.
     */
    private void refresh() {
        showFrameRates();
        showClient();
        showHook();
    }

    private void showStatus() {
        Color dot = switch (attacher.getState()) {
            case ATTACHED -> Theme.ACCENT;
            case ATTACHING -> Theme.WARNING;
            case UNSUPPORTED, FAILED -> Theme.ERROR;
            case WAITING -> Theme.MEDIUM;
        };
        status.setIcon(new Dot(dot));
        status.setPlainText(attacher.getDetail());
    }

    private void showFrameRates() {
        double rate = frameRates.getGameFrameRate();
        int limit = frameRates.getFrameLimit();
        gameFps.setPlainText(Double.isNaN(rate) ? UNKNOWN
                : Math.round(rate) + (limit > 0 ? " (capped at " + limit + ")" : ""));
        double overlays = frameRates.getOverlayFrameRate();
        overlayFps.setPlainText(overlays > 0 ? String.valueOf(Math.round(overlays)) : "idle");
    }

    private void showClient() {
        long id = attacher.getPid();
        pid.setPlainText(id > 0 ? String.valueOf(id) : UNKNOWN);
        ClientBuild read = attacher.getBuild();
        clientVersion.setPlainText(read != null ? read.version() : UNKNOWN);
        build.setPlainText(read != null ? read.sha256().substring(0, BUILD_CHARS) : UNKNOWN);
        tip(build, read != null ? "SHA-256 " + read.sha256() : null);
        String source = attacher.getMappingSource();
        boolean bundled = ProcessAttacher.BUNDLED_MAPPINGS.equals(source);
        mapping.setPlainText(source == null ? UNKNOWN : bundled ? "Bundled" : "Custom");
        tip(mapping, source == null || bundled ? null : "From kraken.mappings: " + source);
    }

    private void showHook() {
        NativeEventServer server = hook;
        NativeEventServer.Status now = server != null ? server.getStatus() : null;
        hookState.setPlainText(now == null ? UNKNOWN : switch (now) {
            case NOT_INSTALLED -> "Not installed";
            case INJECTING -> "Injecting…";
            case CONNECTED -> "Connected";
            case DISCONNECTED -> "Reconnecting…";
            case UNAVAILABLE -> "Not loaded";
        });
        tip(hookState, now == NativeEventServer.Status.UNAVAILABLE
                ? "Events come from the timer poll alone. The console says why." : null);
        NativeEventServer.Report report = server != null ? server.getReport() : null;
        hooks.setPlainText(report == null ? UNKNOWN : report.installed() + " of " + report.requested());
        tip(hooks, report == null ? null : "Protocol " + report.protocol());
        features.setPlainText(report == null ? UNKNOWN
                : report.features().isEmpty() ? "none" : String.join(", ", report.features()));
    }

    /**
     * Shows how many of the plugins the Plugins page lists are on, and how many failed. EDT.
     */
    private void showPlugins() {
        List<Plugin> listed = plugins.getPlugins().stream()
                .filter(plugin -> !plugin.getClass().getAnnotation(PluginDescriptor.class).hidden())
                .toList();
        if (listed.isEmpty()) {
            pluginCount.setPlainText(UNKNOWN);
            return;
        }
        long on = listed.stream().filter(plugins::isPluginActive).count();
        long failed = listed.stream().filter(plugin -> plugins.getFailure(plugin) != null).count();
        pluginCount.setPlainText(on + " of " + listed.size() + " on" + (failed > 0 ? ", " + failed + " failed" : ""));
    }

    /**
     * Reads the game while the page is showing, and hands what changed to the EDT. API poll thread.
     *
     * @param session the attached API
     */
    private void readGame(GameSession session) {
        if (!showing) {
            return;
        }
        GameFacts facts;
        try {
            facts = GameFacts.read(session.client());
        } catch (RuntimeException e) {
            // Unreadable mid-login or as the game exits: the page keeps the last facts until the next poll
            return;
        }
        if (!facts.equals(game)) {
            game = facts;
            SwingUtilities.invokeLater(() -> showGame(facts));
        }
    }

    private void showGame(GameFacts facts) {
        state.setPlainText(facts.equals(GameFacts.NONE) ? UNKNOWN : facts.stateText());
        player.setPlainText(text(facts.player()));
        world.setPlainText(facts.world() > 0 ? String.valueOf(facts.world()) : UNKNOWN);
        worldType.setPlainText(text(facts.typesText()));
    }

    private void browse(String url) {
        background.execute(() -> {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Could not open " + url, e);
            }
        });
    }

    private void open(Path folder) {
        background.execute(() -> {
            try {
                Files.createDirectories(folder);
                Desktop.getDesktop().open(folder.toFile());
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Could not open " + folder, e);
            }
        });
    }

    private static String text(String value) {
        return value != null ? value : UNKNOWN;
    }

    private static void tip(JComponent component, String tip) {
        if (!Objects.equals(component.getToolTipText(), tip)) {
            component.setToolTipText(tip);
        }
    }

    /**
     * A folder for showing, with the home folder written as {@code ~}.
     *
     * @param folder the folder
     * @return the shortened path
     */
    private static String shortPath(Path folder) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        return folder.startsWith(home) ? "~" + File.separator + home.relativize(folder) : folder.toString();
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new EmptyBorder(16, 2, 6, 0));
        return label;
    }

    private static String cardStyle(Color background) {
        return "arc: 12; background: " + Theme.hex(background);
    }

    /**
     * A link: an icon, a title over a line of detail, and an arrow. Lights up while hovered, and runs its action when
     * clicked.
     *
     * @param ikon        the icon
     * @param title       what it does
     * @param description the detail under the title
     * @param tooltip     where it goes
     * @param action      what clicking it does
     * @return the link
     */
    private static JComponent link(Ikon ikon, String title, String description, String tooltip, Runnable action) {
        JPanel link = new JPanel(new BorderLayout(10, 0));
        link.setBorder(new EmptyBorder(10, 12, 10, 8));
        link.putClientProperty(STYLE, cardStyle(Theme.DARKER));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(tooltip);

        JLabel name = new JLabel(title);
        WrapLabel detail = new WrapLabel(description);
        detail.putClientProperty(STYLE_CLASS, "small");
        detail.setForeground(Theme.LIGHT);
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(detail, BorderLayout.CENTER);

        link.add(new JLabel(FontIcon.of(ikon, 22, Theme.TEXT)), BorderLayout.WEST);
        link.add(text, BorderLayout.CENTER);
        link.add(new JLabel(FontIcon.of(MaterialDesignC.CHEVRON_RIGHT, 18, Theme.LIGHT)), BorderLayout.EAST);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                link.putClientProperty(STYLE, cardStyle(Theme.HOVER));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                link.putClientProperty(STYLE, cardStyle(Theme.DARKER));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && link.contains(e.getPoint())) {
                    action.run();
                }
            }
        });
        return link;
    }

    /**
     * A rounded card of rows, each a name on the left and its value on the right. A long value wraps.
     */
    private static final class Card extends JPanel {

        Card() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(8, 12, 8, 12));
            putClientProperty(STYLE, cardStyle(Theme.DARKER));
        }

        /**
         * Adds a row.
         *
         * @param name  the row's name
         * @param value the value to show first
         * @return the value's label
         */
        WrapLabel row(String name, String value) {
            JLabel label = new JLabel(name);
            label.setForeground(Theme.LIGHT);
            label.setVerticalAlignment(SwingConstants.TOP);
            WrapLabel shown = new WrapLabel(value, SwingConstants.RIGHT);
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(4, 0, 4, 0));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(label, BorderLayout.WEST);
            row.add(shown, BorderLayout.CENTER);
            add(row);
            return shown;
        }
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
}
