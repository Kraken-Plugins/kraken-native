package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.GameView;
import com.kraken.client.HomePanel;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;
import static com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE;

/** RuneLite-style main window: the game with the sidebar on its right, and the log console docked underneath. */
@Singleton
public final class KrakenFrame extends JFrame {
    @Inject
    KrakenFrame(GameView view, HomePanel home, Sidebar sidebar, ConsolePanel console) {
        super("Kraken");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
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
        sidebar.addTab("Plugins", MaterialDesignP.PUZZLE_OUTLINE, page("Plugins", emptyState(MaterialDesignP.PUZZLE_OUTLINE,
                "No plugins yet", "Plugins built on the Kraken API will show up here.")));
        sidebar.addTab("Settings", MaterialDesignC.COG_OUTLINE, page("Settings", settings(sidebar)));

        JPanel main = new JPanel(new BorderLayout());
        main.add(view, BorderLayout.CENTER);
        main.add(sidebar, BorderLayout.EAST);
        add(main, BorderLayout.CENTER);
        add(console, BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(480, 360));
        setLocationRelativeTo(null);
    }

    private JComponent settings(Sidebar sidebar) {
        Box list = Box.createVerticalBox();
        list.add(setting("Always on top", "Keep Kraken above other windows.", false, this::setAlwaysOnTop));
        list.add(setting("Grow window for panels", "Widen the window when a panel opens instead of shrinking the game.",
                sidebar.isGrowWindow(), sidebar::setGrowWindow));
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
