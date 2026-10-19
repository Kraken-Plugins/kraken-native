package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.WrapLabel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

import static com.formdev.flatlaf.FlatClientProperties.STYLE;
import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;

/**
 * Sidebar landing page: a card of live facts about the embedded client, with the API connection status beneath it.
 * {@link #set} is thread-safe.
 */
@Singleton
public final class HomePanel extends JPanel {
    private final JPanel facts = new JPanel();
    private final Map<String, JLabel> values = new HashMap<>();
    private final WrapLabel status = new WrapLabel("");
    private final ApiConnection connection;

    @Inject
    HomePanel(ApiConnection connection) {
        super(new BorderLayout());
        this.connection = connection;
        setBorder(new EmptyBorder(12, 12, 12, 12));
        facts.setLayout(new BoxLayout(facts, BoxLayout.Y_AXIS));
        facts.setBorder(new EmptyBorder(8, 12, 8, 12));
        facts.putClientProperty(STYLE, "arc: 12; background: " + Theme.hex(Theme.DARKER));

        status.putClientProperty(STYLE_CLASS, "small");
        status.setForeground(Theme.LIGHT);
        status.setIconTextGap(6);
        status.setVerticalTextPosition(SwingConstants.TOP);
        status.setBorder(new EmptyBorder(8, 2, 0, 0));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{facts, status}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            north.add(c);
        }
        add(north, BorderLayout.NORTH);

        connection.addStateListener(this::showStatus);
        showStatus();
    }

    /**
     * Sets a fact row, adding it on first use (rows keep insertion order).
     *
     * @param key   the row's label
     * @param value the text to show for it
     */
    public void set(String key, String value) {
        SwingUtilities.invokeLater(() -> values.computeIfAbsent(key, k -> {
            JLabel label = new JLabel(k);
            label.setForeground(Theme.LIGHT);
            JLabel v = new JLabel();
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(4, 0, 4, 0));
            row.add(label, BorderLayout.WEST);
            row.add(v, BorderLayout.EAST);
            facts.add(row);
            facts.revalidate();
            return v;
        }).setText(value));
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
