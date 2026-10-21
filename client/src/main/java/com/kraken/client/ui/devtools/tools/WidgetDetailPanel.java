package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.View;
import javax.swing.text.html.HTMLDocument;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The selected node's detail: a breadcrumb trail from its root, its name, id and state, the code that fetches it, and
 * its fields by section. The header is rebuilt when the node changes; the fields are updated in place while their
 * labels stay the same, so selected text survives the game's updates.
 */
final class WidgetDetailPanel extends JPanel implements Scrollable {

    private static final Color CODE_FILL = Theme.DARKEST;
    private static final Color CODE_EDGE = new Color(0x333333);
    private static final Color SEPARATOR = new Color(0x5c5c5c);

    private final Consumer<String> select;
    private final JPanel header = new JPanel(new GridBagLayout());
    private final JPanel fields = new JPanel(new GridBagLayout());
    private final JLabel notice = new JLabel();
    private final List<Value> values = new ArrayList<>();
    private List<Row> shownPath;
    private List<String> shownLayout;
    private WidgetDetail shownDetail;

    /**
     * A value already on the panel, updated in place.
     *
     * @param component the label or text area showing it
     * @param field     the field it shows
     */
    private record Value(JComponent component, WidgetDetail.Field field) {
    }

    /**
     * An empty panel.
     *
     * @param select called with a node's key when its breadcrumb is clicked
     */
    WidgetDetailPanel(Consumer<String> select) {
        super(new GridBagLayout());
        this.select = select;
        setBackground(Theme.DARKER);
        setBorder(new EmptyBorder(14, 16, 18, 16));
        header.setOpaque(false);
        fields.setOpaque(false);
        notice.setForeground(Theme.LIGHT);
        notice.setBorder(new EmptyBorder(14, 0, 0, 0));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridy = 0;
        add(header, c);
        c.gridy = 1;
        add(notice, c);
        c.gridy = 2;
        add(fields, c);
        c.gridy = 3;
        c.weighty = 1;
        add(Box.createGlue(), c);
    }

    /**
     * Shows a node.
     *
     * @param path   the rows from a root down to the node; empty for no selection
     * @param detail its fields, or null while they are being read
     * @param names  the cache's names
     */
    void show(List<Row> path, WidgetDetail detail, WidgetNames names) {
        if (shownPath != null && path.equals(shownPath) && Objects.equals(detail, shownDetail)) {
            return;
        }
        if (!path.equals(shownPath) || path.isEmpty()) {
            boolean otherNode = shownPath == null || path.isEmpty() || !lastKey(path).equals(lastKey(shownPath));
            buildHeader(path, detail, names);
            shownPath = path;
            if (otherNode) {
                SwingUtilities.invokeLater(() -> scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
            }
        }
        shownDetail = detail;
        if (detail == null || detail.gone()) {
            notice.setText(detail == null ? (path.isEmpty() ? "" : "Reading…")
                    : "Not there any more: its interface closed, or a script removed it.");
            notice.setVisible(!notice.getText().isEmpty());
            fields.removeAll();
            values.clear();
            shownLayout = null;
        } else {
            notice.setVisible(false);
            showFields(detail);
        }
        revalidate();
        repaint();
    }

    private static String lastKey(List<Row> path) {
        return path.isEmpty() ? "" : path.get(path.size() - 1).key();
    }

    /**
     * A text area for a value: read-only, wrapping, and never scrolling the panel to its caret when its text changes.
     *
     * @param text the text
     * @return the area
     */
    private JTextArea textArea(String text) {
        JTextArea area = new JTextArea(text);
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        area.setFont(WidgetInspectorView.mono(getFont().getSize2D() - 1f));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private void buildHeader(List<Row> path, WidgetDetail detail, WidgetNames names) {
        header.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridy = 0;
        if (path.isEmpty()) {
            if (detail == null) {
                header.add(emptyState(), c);
            }
            return;
        }
        Row row = path.get(path.size() - 1);
        if (path.size() > 1) {
            header.add(crumbs(path), c);
            c.gridy++;
        }

        JLabel title = new JLabel(WidgetInspectorTool.title(path));
        title.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 6f));
        title.setForeground(Theme.TEXT);
        title.setBorder(new EmptyBorder(path.size() > 1 ? 6 : 0, 0, 2, 0));
        header.add(title, c);
        c.gridy++;

        JLabel ids = new JLabel(subtitle(row, names));
        ids.setFont(WidgetInspectorView.mono(getFont().getSize2D() - 1f));
        ids.setForeground(Theme.LIGHT);
        header.add(ids, c);
        c.gridy++;

        if (!row.summary().isEmpty() && !row.isGroup()) {
            JLabel summary = new JLabel(row.summary());
            summary.setForeground(Theme.TEXT);
            summary.setBorder(new EmptyBorder(6, 0, 0, 0));
            header.add(summary, c);
            c.gridy++;
        }

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        chips.setBorder(new EmptyBorder(10, -6, 0, 0));
        chips.add(chip(row.isGroup() ? "INTERFACE" : WidgetType.name(row.type()),
                WidgetInspectorView.typeColor(row.type())));
        if (!row.isGroup()) {
            chips.add(chip(row.kind() == Kind.STATIC ? "static" : "dynamic", Theme.LIGHT));
        }
        if (row.selfHidden()) {
            chips.add(chip("hidden", Theme.WARNING));
        } else if (row.hidden()) {
            chips.add(chip("hidden by a parent", Theme.WARNING));
        } else if (row.drawn()) {
            chips.add(chip("drawn", Theme.ACCENT));
        } else {
            chips.add(chip("not drawn", Theme.LIGHT));
        }
        header.add(chips, c);
        c.gridy++;

        c.insets = new Insets(12, 0, 0, 0);
        header.add(code(WidgetForest.code(path, names)), c);
    }

    /**
     * The id line under the title.
     *
     * @param row   the node's row
     * @param names the cache's names
     * @return e.g. {@code 149:0 · InterfaceID.Inventory.ITEMS}
     */
    private static String subtitle(Row row, WidgetNames names) {
        if (row.isGroup()) {
            String name = names.group(row.id());
            return "Interface " + row.id() + (name.isEmpty() ? "" : "  ·  InterfaceID." + name);
        }
        WidgetNames.Name name = row.kind() == Kind.STATIC ? names.component(row.id()) : null;
        return row.key() + "  ·  0x" + String.format("%08x", row.id())
                + (name == null ? "" : "  ·  " + name.constant());
    }

    /**
     * The trail of links from the root down to the node.
     *
     * @param path the rows from a root down to the node
     * @return the trail
     */
    private JComponent crumbs(List<Row> path) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < path.size(); i++) {
            Row row = path.get(i);
            String label = Theme.escape(crumb(row));
            if (i > 0) {
                html.append(" <span style='color:").append(Theme.hex(SEPARATOR)).append("'>›</span> ");
            }
            if (i == path.size() - 1) {
                html.append("<span style='color:").append(Theme.hex(Theme.TEXT)).append("'>").append(label)
                        .append("</span>");
            } else {
                html.append("<a href='").append(Theme.escape(row.key())).append("'>").append(label).append("</a>");
            }
        }
        JEditorPane pane = new JEditorPane("text/html", "");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setFont(UIManager.getFont("small.font") != null ? UIManager.getFont("small.font") : getFont());
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setForeground(Theme.LIGHT);
        ((HTMLDocument) pane.getDocument()).getStyleSheet()
                .addRule("a { color: " + Theme.hex(Theme.LIGHT) + "; text-decoration: none; }");
        pane.setText(html.append("</body></html>").toString());
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                select.accept(e.getDescription());
            }
        });
        pane.setCursor(Cursor.getDefaultCursor());
        return pane;
    }

    private static String crumb(Row row) {
        return switch (row.kind()) {
            case GROUP -> row.name().isEmpty() ? Integer.toString(row.id()) : row.name();
            case STATIC -> row.name().isEmpty() ? row.idText() : row.name();
            case DYNAMIC -> row.idText();
        };
    }

    /**
     * A small rounded label.
     *
     * @param text  the label
     * @param color its colour
     * @return the chip
     */
    private JLabel chip(String text, Color color) {
        JLabel chip = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1,
                        getHeight(), getHeight());
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
                g.fill(shape);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 110));
                g.draw(shape);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        Font small = UIManager.getFont("small.font");
        chip.setFont((small != null ? small : getFont()).deriveFont(Font.BOLD));
        chip.setForeground(color);
        chip.setBorder(new EmptyBorder(2, 9, 2, 9));
        return chip;
    }

    /**
     * The code that fetches the node, in a box with a copy button.
     *
     * @param code the code
     * @return the box
     */
    private JComponent code(String code) {
        // Each step down to a dynamic child on its own line, so the chain never breaks inside a name
        JTextArea text = textArea(code.replace(").getChild(", ")\n        .getChild("));
        text.setForeground(new Color(0xc9d1d9));
        text.setWrapStyleWord(true);

        Icon copyIcon = FontIcon.of(MaterialDesignC.CONTENT_COPY, 15, Theme.LIGHT);
        Icon doneIcon = FontIcon.of(MaterialDesignC.CHECK, 15, Theme.ACCENT);
        JButton copy = new JButton(copyIcon);
        copy.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        copy.setToolTipText("Copy");
        copy.setFocusable(false);
        Timer reset = new Timer(1200, e -> copy.setIcon(copyIcon));
        reset.setRepeats(false);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
            copy.setIcon(doneIcon);
            reset.restart();
        });

        JPanel box = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(CODE_FILL);
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g.setColor(CODE_EDGE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
                g.dispose();
            }
        };
        box.setOpaque(false);
        box.setBorder(new EmptyBorder(8, 12, 8, 6));
        box.add(new Wrapped(text), BorderLayout.CENTER);
        JPanel side = new JPanel(new BorderLayout());
        side.setOpaque(false);
        side.add(copy, BorderLayout.NORTH);
        box.add(side, BorderLayout.EAST);
        return box;
    }

    private JComponent emptyState() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(60, 10, 0, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel(FontIcon.of(MaterialDesignW.WIDGETS_OUTLINE, 40, Theme.MEDIUM)), c);
        c.gridy = 1;
        c.insets = new Insets(12, 0, 0, 0);
        JLabel title = new JLabel("Nothing selected");
        title.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 2f));
        title.setForeground(Theme.TEXT);
        panel.add(title, c);
        c.gridy = 2;
        c.insets = new Insets(6, 0, 0, 0);
        JLabel hint = new JLabel("<html><div style='text-align:center'>Choose a widget in the tree, or press "
                + "<b>Pick</b> and click one in the game.</div></html>");
        hint.setForeground(Theme.LIGHT);
        panel.add(hint, c);
        return panel;
    }

    /**
     * Shows the fields: in place if the sections and labels are the ones already shown, else laid out again.
     *
     * @param detail the fields
     */
    private void showFields(WidgetDetail detail) {
        List<String> layout = new ArrayList<>();
        for (WidgetDetail.Section section : detail.sections()) {
            layout.add("#" + section.title());
            for (WidgetDetail.Field field : section.fields()) {
                layout.add(field.label() + (field.swatch() != null ? "|swatch" : ""));
            }
        }
        if (layout.equals(shownLayout)) {
            int i = 0;
            for (WidgetDetail.Section section : detail.sections()) {
                for (WidgetDetail.Field field : section.fields()) {
                    update(values.get(i++), field);
                }
            }
            return;
        }
        shownLayout = layout;
        fields.removeAll();
        values.clear();
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridy = 0;
        for (WidgetDetail.Section section : detail.sections()) {
            JLabel title = new JLabel(section.title().toUpperCase(Locale.ROOT));
            Font small = UIManager.getFont("small.font");
            title.setFont((small != null ? small : getFont()).deriveFont(Font.BOLD));
            title.setForeground(Theme.LIGHT);
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(c.gridy == 0 ? 16 : 18, 0, 6, 0);
            fields.add(title, c);
            c.gridy++;
            c.gridwidth = 1;
            for (WidgetDetail.Field field : section.fields()) {
                JLabel label = new JLabel(field.label());
                label.setForeground(Theme.LIGHT);
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                c.insets = new Insets(3, 0, 3, 16);
                fields.add(label, c);
                JComponent value = value(field);
                c.gridx = 1;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(3, 0, 3, 0);
                fields.add(value, c);
                values.add(new Value(value, field));
                c.gridy++;
            }
        }
    }

    private JComponent value(WidgetDetail.Field field) {
        JComponent component;
        if (field.swatch() != null) {
            JLabel label = new JLabel();
            label.setFont(WidgetInspectorView.mono(getFont().getSize2D() - 1f));
            label.setForeground(Theme.TEXT);
            label.setIconTextGap(8);
            component = label;
        } else {
            JTextArea text = textArea("");
            text.setForeground(Theme.TEXT);
            text.setWrapStyleWord(true);
            component = new Wrapped(text);
        }
        update(new Value(component, null), field);
        return component;
    }

    private static void update(Value value, WidgetDetail.Field field) {
        if (value.component() instanceof JLabel label) {
            label.setText(field.value());
            label.setIcon(swatch(field.swatch()));
            label.setToolTipText(field.tooltip());
        } else if (value.component() instanceof Wrapped wrapped) {
            if (!wrapped.text.getText().equals(field.value())) {
                wrapped.text.setText(field.value());
            }
            wrapped.text.setToolTipText(field.tooltip());
        }
    }

    private static Icon swatch(Color color) {
        if (color == null) {
            return null;
        }
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.fill(new RoundRectangle2D.Float(x, y, 14, 14, 4, 4));
                g.setColor(new Color(255, 255, 255, 60));
                g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 13, 13, 4, 4));
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return 14;
            }

            @Override
            public int getIconHeight() {
                return 14;
            }
        };
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - 32 : visibleRect.width - 32;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }

    /**
     * A wrapping text area that asks only for the height its text needs at the width it is given, so the layout
     * hands it the column's width rather than its text's unwrapped length.
     */
    private static final class Wrapped extends JPanel {
        private final JTextArea text;
        private int laidOutWidth = -1;

        Wrapped(JTextArea text) {
            super(new BorderLayout());
            this.text = text;
            setOpaque(false);
            add(text, BorderLayout.CENTER);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (getWidth() != laidOutWidth) {
                        laidOutWidth = getWidth();
                        revalidate();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int width = Math.max(40, (getWidth() > 0 ? getWidth() : 280) - insets.left - insets.right);
            View view = text.getUI().getRootView(text);
            view.setSize(width, Float.MAX_VALUE / 2);
            int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
            Insets textInsets = text.getInsets();
            return new Dimension(1, height + textInsets.top + textInsets.bottom + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }
}
