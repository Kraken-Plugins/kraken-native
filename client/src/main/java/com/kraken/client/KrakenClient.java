package com.kraken.client;

import com.sun.jna.platform.win32.WinDef.HWND;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;
import static com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE;

/**
 * Proof of concept: launches osclient.exe and re-parents its window into a RuneLite-style Swing frame.
 * Usage: KrakenClient [path\to\osclient.exe]
 */
public class KrakenClient {
    private static final String DEFAULT_EXE =
            "C:\\Program Files (x86)\\Jagex Launcher\\Games\\Old School RuneScape\\Client\\osclient.exe";
    private static final Logger log = Logger.getLogger("kraken");

    private static GameView view;
    private static HomePanel home;

    public static void main(String[] args) throws Exception {
        File exe = new File(args.length > 0 ? args[0] : DEFAULT_EXE);
        SwingUtilities.invokeAndWait(KrakenClient::createFrame);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.log(Level.SEVERE, "Uncaught exception on " + t.getName(), e));
        log.info("Kraken starting on Java " + Runtime.version());
        try {
            run(exe);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not launch " + exe, e);
            view.setMessage("Could not launch the client, see the console for details");
        }
    }

    private static void run(File exe) throws Exception {
        String build = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(exe.toPath())), 0, 8);
        home.set("Build", build);
        log.info("Launching " + exe + " (build " + build + ")");

        Process process = new ProcessBuilder(exe.getPath()).directory(exe.getParentFile()).redirectErrorStream(true).start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        process.onExit().thenRun(() -> System.exit(0));
        Logger clientLog = Logger.getLogger("osclient");
        Thread.startVirtualThread(() -> new BufferedReader(new InputStreamReader(process.getInputStream())).lines().forEach(clientLog::info));
        home.set("PID", String.valueOf(process.pid()));
        log.info("osclient started (pid " + process.pid() + "), waiting for its window");

        HWND hwnd;
        while ((hwnd = GameView.find(process.pid())) == null) {
            Thread.sleep(100);
        }
        view.embed(hwnd);

        // The client resizes itself (restoring its saved size, fixed mode, ...), so keep it pinned to the canvas
        while (process.isAlive()) {
            Thread.sleep(250);
            view.fit();
        }
    }

    private static void createFrame() {
        Theme.install();

        JFrame frame = new JFrame("Kraken");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setIconImages(Stream.of(16, 20, 24, 30, 32, 40, 48, 64, 128, 256).map(Theme::logo).toList());
        frame.getRootPane().putClientProperty(TITLE_BAR_SHOW_TITLE, false);

        ConsolePanel console = new ConsolePanel();
        Logger.getLogger("").addHandler(console.handler);

        home = new HomePanel();
        home.set("PID", "–");
        home.set("Build", "–");
        home.set("Game size", "–");
        view = new GameView();
        view.addPropertyChangeListener("gameSize", e -> {
            Dimension size = (Dimension) e.getNewValue();
            home.set("Game size", size.width + " × " + size.height);
        });

        Sidebar sidebar = new Sidebar();
        sidebar.addTab("Kraken", Theme.logoIcon(26), Theme.logoIcon(26), home);
        sidebar.addTab("Plugins", MaterialDesignP.PUZZLE_OUTLINE, page("Plugins", emptyState(MaterialDesignP.PUZZLE_OUTLINE,
                "No plugins yet", "Plugins built on the Kraken API will show up here.")));
        sidebar.addTab("Settings", MaterialDesignC.COG_OUTLINE, page("Settings", settings(frame, sidebar)));

        JPanel main = new JPanel(new BorderLayout());
        main.add(view, BorderLayout.CENTER);
        main.add(sidebar, BorderLayout.EAST);
        frame.add(main, BorderLayout.CENTER);
        frame.add(console, BorderLayout.SOUTH);
        frame.pack();
        frame.setMinimumSize(new Dimension(480, 360));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JComponent settings(JFrame frame, Sidebar sidebar) {
        Box list = Box.createVerticalBox();
        list.add(setting("Always on top", "Keep Kraken above other windows.", false, frame::setAlwaysOnTop));
        list.add(setting("Grow window for panels", "Widen the window when a panel opens instead of shrinking the game.",
                sidebar.growWindow, on -> sidebar.growWindow = on));
        JPanel top = new JPanel(new BorderLayout());
        top.add(list, BorderLayout.NORTH);
        return top;
    }

    private static JComponent setting(String title, String description, boolean initial, Consumer<Boolean> onChange) {
        JCheckBox box = new JCheckBox(title, initial);
        box.addActionListener(e -> onChange.accept(box.isSelected()));
        JLabel hint = new JLabel(html(description, 145, "left"));
        hint.putClientProperty(STYLE_CLASS, "small");
        hint.setForeground(Theme.LIGHT);
        hint.setBorder(new EmptyBorder(2, 26, 14, 0));
        JPanel row = new JPanel(new BorderLayout());
        row.add(box, BorderLayout.NORTH);
        row.add(hint, BorderLayout.CENTER);
        return row;
    }

    private static JComponent emptyState(Ikon ikon, String title, String text) {
        JLabel label = new JLabel(html("<b>" + title + "</b><br><br>" + text, 160, "center"), FontIcon.of(ikon, 48, Theme.MEDIUM), SwingConstants.CENTER);
        label.setVerticalTextPosition(SwingConstants.BOTTOM);
        label.setHorizontalTextPosition(SwingConstants.CENTER);
        label.setIconTextGap(12);
        label.setForeground(Theme.LIGHT);
        return label;
    }

    /** Wrapping label text. Swing's HTML renderer scales CSS px by 1.3, so 160px is about 208 real pixels. */
    private static String html(String body, int width, String align) {
        return "<html><div style='width:" + width + "px;text-align:" + align + "'>" + body + "</div></html>";
    }

    /** A sidebar panel with a RuneLite-style title. */
    private static JComponent page(String title, JComponent body) {
        JLabel header = new JLabel(title);
        header.putClientProperty(STYLE_CLASS, "h3");
        header.setBorder(new EmptyBorder(0, 0, 12, 0));
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(new EmptyBorder(12, 12, 12, 12));
        page.add(header, BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        return page;
    }
}
