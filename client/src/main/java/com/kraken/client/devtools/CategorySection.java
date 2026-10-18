package com.kraken.client.devtools;

import com.kraken.client.ui.Theme;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/** A collapsible group of tool rows. Shows how many of its tools are on, so a collapsed section still says so. */
final class CategorySection extends JPanel {
    private static final Icon EXPANDED = FontIcon.of(MaterialDesignC.CHEVRON_DOWN, 14, Theme.LIGHT);
    private static final Icon COLLAPSED = FontIcon.of(MaterialDesignC.CHEVRON_RIGHT, 14, Theme.LIGHT);

    private final List<ToolRow> rows = new ArrayList<>();
    private final JPanel body = new JPanel();
    private final JLabel chevron = new JLabel();
    private final JLabel count = new JLabel();
    private final Preferences prefs;
    private final String collapsedKey;
    private boolean collapsed;
    private boolean filtering;

    CategorySection(Category category, List<DevTool<?>> tools, DevToolManager manager) {
        super(new BorderLayout());
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        prefs = manager.getPrefs();
        collapsedKey = "section." + category.name() + ".collapsed";
        collapsed = prefs.getBoolean(collapsedKey, false);

        Font small = UIManager.getFont("small.font");
        JLabel title = new JLabel(category.getTitle().toUpperCase(Locale.ROOT));
        title.setFont(small.deriveFont(Font.BOLD));
        title.setForeground(Theme.LIGHT);
        count.setFont(small);
        count.setForeground(Theme.ACCENT);

        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(10, 2, 4, 10));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.add(chevron, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        header.add(count, BorderLayout.EAST);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!filtering && SwingUtilities.isLeftMouseButton(e)) {
                    collapsed = !collapsed;
                    prefs.putBoolean(collapsedKey, collapsed);
                    updateBody();
                }
            }
        });

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        for (DevTool<?> tool : tools) {
            ToolRow row = new ToolRow(tool, manager);
            row.setAlignmentX(LEFT_ALIGNMENT);
            rows.add(row);
            body.add(row);
        }

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        updateBody();
    }

    /**
     * Shows only the rows matching {@code query} (lower case; empty matches all), hiding planned tools unless
     * {@code showPlanned}. While filtering, the section stays open so matches are visible. Returns the rows shown.
     */
    int filter(String query, boolean showPlanned) {
        filtering = !query.isEmpty();
        int shown = 0;
        for (ToolRow row : rows) {
            boolean show = row.matches(query) && (showPlanned || !row.isPlanned());
            row.setVisible(show);
            shown += show ? 1 : 0;
        }
        setVisible(shown > 0);
        updateBody();
        return shown;
    }

    void refreshState() {
        rows.forEach(ToolRow::refreshState);
        long active = rows.stream().filter(ToolRow::isActive).count();
        count.setText(active > 0 ? active + " on" : "");
    }

    private void updateBody() {
        boolean open = filtering || !collapsed;
        body.setVisible(open);
        chevron.setIcon(open ? EXPANDED : COLLAPSED);
        revalidate();
        repaint();
    }
}
