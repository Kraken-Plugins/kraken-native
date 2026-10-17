package com.kraken.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

import static com.formdev.flatlaf.FlatClientProperties.STYLE;

/** Sidebar landing page: a card of live facts about the embedded client. {@link #set} is thread-safe. */
final class HomePanel extends JPanel {
    private final JPanel facts = new JPanel();
    private final Map<String, JLabel> values = new HashMap<>();

    HomePanel() {
        super(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        facts.setLayout(new BoxLayout(facts, BoxLayout.Y_AXIS));
        facts.setBorder(new EmptyBorder(8, 12, 8, 12));
        facts.putClientProperty(STYLE, "arc: 12; background: " + Theme.hex(Theme.DARKER));
        add(facts, BorderLayout.NORTH);
    }

    /** Sets a fact row, adding it on first use (rows keep insertion order). */
    void set(String key, String value) {
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
}
