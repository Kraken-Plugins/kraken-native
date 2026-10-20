package com.kraken.client.ui.devtools.view;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import static com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS;

/**
 * Live label/value pairs, for tools that show one thing's current state (your location, the camera…).
 *
 * @param <S> the tool's snapshot type; {@code facts} turns it into ordered rows
 */
public final class FactsView<S> implements ToolView<S> {
    private final Function<S, Map<String, String>> facts;
    private final Map<String, JLabel> values = new LinkedHashMap<>();
    private final JPanel grid = new JPanel(new GridBagLayout());
    private final JPanel root = new JPanel(new BorderLayout());

    public FactsView(Function<S, Map<String, String>> facts) {
        this.facts = facts;
        grid.setBorder(new EmptyBorder(4, 14, 14, 14));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        toolbar.setBorder(new EmptyBorder(6, 8, 0, 8));
        toolbar.add(Controls.toolButton(MaterialDesignC.CONTENT_COPY, "Copy all", e -> copy()));

        JPanel top = new JPanel(new BorderLayout());
        top.add(grid, BorderLayout.NORTH);
        root.add(toolbar, BorderLayout.NORTH);
        root.add(top, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(320, 200));
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(S snapshot) {
        Map<String, String> rows = facts.apply(snapshot);
        if (!new ArrayList<>(rows.keySet()).equals(new ArrayList<>(values.keySet()))) {
            rebuild(rows);
        }
        rows.forEach((key, value) -> values.get(key).setText(value));
    }

    private void rebuild(Map<String, String> rows) {
        grid.removeAll();
        values.clear();
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 16);
        c.gridy = 0;
        for (String key : rows.keySet()) {
            JLabel label = new JLabel(key);
            label.setForeground(Theme.LIGHT);
            JLabel value = new JLabel();
            value.putClientProperty(STYLE_CLASS, "monospaced");
            value.setForeground(Theme.TEXT);
            c.gridx = 0;
            c.weightx = 0;
            grid.add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            grid.add(value, c);
            c.gridy++;
            values.put(key, value);
        }
        grid.revalidate();
        grid.repaint();
    }

    private void copy() {
        String text = values.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().getText())
                .collect(Collectors.joining("\n"));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
