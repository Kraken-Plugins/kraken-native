package com.kraken.client.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.ExitHandler;
import com.kraken.client.GameView;
import com.kraken.client.HomePanel;
import com.kraken.client.devtools.DevToolsPanel;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;
import static com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE;

/**
 * RuneLite-style main window: the game with the sidebar on its right, and the log console docked underneath. Closing
 * it closes Kraken through the {@link ExitHandler}.
 */
@Singleton
public final class KrakenFrame extends JFrame {
    @Inject
    KrakenFrame(GameView view, HomePanel home, Sidebar sidebar, ConsolePanel console, DevToolsPanel devTools,
                PluginListPanel plugins, ExitHandler exitHandler) {
        super("Kraken");
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
        WrapLabel hint = new WrapLabel(description);
        hint.putClientProperty(STYLE_CLASS, "small");
        hint.setForeground(Theme.LIGHT);
        hint.setBorder(new EmptyBorder(2, 26, 14, 0));
        JPanel row = new JPanel(new BorderLayout());
        row.add(box, BorderLayout.NORTH);
        row.add(hint, BorderLayout.CENTER);
        return row;
    }

    /**
     * A sidebar panel with a RuneLite-style title.
     *
     * @param title the panel's title
     * @param body  what goes under it
     * @return the panel
     */
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
