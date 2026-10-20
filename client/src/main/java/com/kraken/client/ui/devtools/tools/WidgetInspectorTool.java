package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.Point;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetType;
import com.kraken.api.widgets.WidgetUtil;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Browses one open interface group as a tree: its static components (nested by their parent) with their dynamic
 * children under them. The selected component is outlined over the game, and optionally every component the client
 * drew this frame. The window also shows the selected component's detail: its id, parent, hidden state with its
 * parents, bounds, item and actions.
 */
final class WidgetInspectorTool extends DevTool<WidgetInspectorTool.Snapshot> {
    private static final int MAX_ROWS = 5_000;
    private static final int DEFAULT_GROUP = 149;
    private static final Color SELECTED = new Color(0x3cdcff);
    private static final Color DRAWN = new Color(255, 255, 255, 90);

    /**
     * One component in tree order.
     *
     * @param key       the component's path from its static root, unique among the rows, e.g. {@code 162:57/[3]}
     * @param tree      the id indented by depth
     * @param type      the widget type's name
     * @param parent    the parent's id, e.g. {@code 162:57}, or ""
     * @param x         x relative to the parent
     * @param y         y relative to the parent
     * @param width     the width
     * @param height    the height
     * @param hidden    hidden by its own flag
     * @param rendering drawn in the client's latest frame
     * @param text      the text, with markup
     * @param item      the item an inventory slot shows ("id xcount"), or ""
     * @param ops       the non-empty op names
     * @param screen    the top-left corner on screen, in client pixels
     */
    record Row(String key, String tree, String type, String parent, int x, int y, int width, int height,
               boolean hidden, boolean rendering, String text, String item, String ops, Point screen) {
    }

    /**
     * What the window and overlay show.
     *
     * @param groups the open groups
     * @param group  the group shown
     * @param rows   its components in tree order
     * @param marks  outlines over the game
     * @param detail the selected component's detail, or null if none is selected
     */
    record Snapshot(List<Integer> groups, int group, List<Row> rows, Marks marks, String detail) {
    }

    private final ToolOption allDrawn = option("drawn", "Outline everything drawn", false);

    /**
     * Chosen in the window (EDT), read on the poll thread.
     */
    private volatile int group = -1;
    private volatile String selected;

    WidgetInspectorTool() {
        super("widgets", "Widget inspector", Category.INTERFACE, MaterialDesignW.WIDGETS_OUTLINE,
                "Browse an open interface's components as a tree with their type, position, text, item and actions, "
                        + "and outline the selected one in game. The selected one's id, parent, hidden state, bounds, "
                        + "item and actions are shown under the list.");
    }

    @Override
    public Support overlaySupport() {
        return Support.READY;
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected Snapshot read(KrakenApi api) {
        Client client = api.client();
        List<Integer> open = client.getOpenWidgetGroups();
        if (open.isEmpty()) {
            return null;
        }
        int shown = group;
        if (!open.contains(shown)) {
            shown = open.contains(DEFAULT_GROUP) ? DEFAULT_GROUP : open.get(0);
        }
        Widget[] components = client.getWidgetGroup(shown);
        List<Row> rows = new ArrayList<>();
        Marks.Builder marks = isOverlayOn()
                ? new Marks.Builder(new Dimension(client.getCanvasWidth(), client.getCanvasHeight())) : null;
        String[] detail = {null};
        if (components != null) {
            new Tree(components, rows, marks, detail).walk();
        }
        return new Snapshot(open, shown, List.copyOf(rows), marks == null ? Marks.NONE : marks.build(), detail[0]);
    }

    /**
     * Lays a group out in tree order. Screen positions are summed on the way down from each root's group offset, which
     * saves reading each parent again.
     */
    private final class Tree {
        private final Widget[] components;
        private final List<Row> rows;
        private final Marks.Builder marks;
        private final String selectedKey = selected;
        private final Map<Integer, List<Widget>> staticChildren = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final String[] detail;

        /**
         * Prepares a walk.
         *
         * @param components the group's components by index, null where there is none
         * @param rows       where the rows go
         * @param marks      where the outlines go, or null
         * @param detail     receives the selected component's detail, in element 0
         */
        Tree(Widget[] components, List<Row> rows, Marks.Builder marks, String[] detail) {
            this.components = components;
            this.rows = rows;
            this.marks = marks;
            this.detail = detail;
        }

        void walk() {
            Set<Integer> ids = new HashSet<>();
            for (Widget c : components) {
                if (c != null) {
                    ids.add(c.getId());
                }
            }
            List<Widget> top = new ArrayList<>();
            for (Widget c : components) {
                if (c == null) {
                    continue;
                }
                if (ids.contains(c.getParentId())) {
                    staticChildren.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
                } else {
                    top.add(c);
                }
            }
            for (Widget c : top) {
                Point origin = origin(c);
                visit(c, 0, origin, componentKey(c));
            }
        }

        /**
         * A root's origin on screen: the group's offset plus its script offset.
         *
         * @param c the root component
         * @return the origin in canvas pixels
         */
        private Point origin(Widget c) {
            Point offset = c.getGroupOffset();
            Point script = c.getGroupScriptOffset();
            int x = (offset == null ? 0 : offset.getX()) + (script == null ? 0 : script.getX());
            int y = (offset == null ? 0 : offset.getY()) + (script == null ? 0 : script.getY());
            return new Point(x, y);
        }

        /**
         * Adds a component's row, then its children's.
         *
         * @param c            the component
         * @param depth        how deep it is in the tree
         * @param parentScreen its parent's top-left corner on screen
         * @param key          its path key
         */
        private void visit(Widget c, int depth, Point parentScreen, String key) {
            if (rows.size() >= MAX_ROWS || !seen.add(key)) {
                return;
            }
            Point screen = new Point(parentScreen.getX() + c.getRelativeX(), parentScreen.getY() + c.getRelativeY());
            boolean rendering = c.isRendering();
            String label = c.isStatic() ? component(c.getId()) : "[" + c.getIndex() + "]";
            rows.add(new Row(key, "  ".repeat(depth) + label, WidgetType.name(c.getType()),
                    c.getParentId() == -1 ? "" : component(c.getParentId()), c.getRelativeX(), c.getRelativeY(),
                    c.getWidth(), c.getHeight(), c.isSelfHidden(), rendering, c.getText(), item(c), ops(c), screen));
            mark(c, key, screen, rendering);
            if (key.equals(selectedKey)) {
                detail[0] = describe(c);
            }
            if (c.isStatic()) {
                for (Widget child : staticChildren.getOrDefault(c.getId(), List.of())) {
                    visit(child, depth + 1, screen, key + "/" + componentKey(child));
                }
            }
            for (Widget child : c.getDynamicChildren()) {
                visit(child, depth + 1, screen, key + "/[" + child.getIndex() + "]");
            }
        }

        private void mark(Widget c, String key, Point screen, boolean rendering) {
            if (marks == null) {
                return;
            }
            boolean isSelected = key.equals(selectedKey);
            if (!isSelected && !(rendering && allDrawn.isOn())) {
                return;
            }
            int x = screen.getX();
            int y = screen.getY();
            Point[] box = {new Point(x, y), new Point(x + c.getWidth(), y),
                    new Point(x + c.getWidth(), y + c.getHeight()), new Point(x, y + c.getHeight())};
            marks.outline(box, isSelected ? SELECTED : DRAWN, null);
            if (isSelected) {
                marks.label(key, new Point(x + c.getWidth() / 2, y - 6), SELECTED);
            }
        }
    }

    /**
     * A static component's key, or a dynamic one's index.
     *
     * @param c the component
     * @return "group:component" for a static one, "[index]" for a dynamic one
     */
    private static String componentKey(Widget c) {
        return c.isStatic() ? component(c.getId()) : "[" + c.getIndex() + "]";
    }

    /**
     * The selected component's detail, as one line.
     *
     * @param c the component
     * @return its id, index, parent, hidden state with its parents, bounds, item and actions
     */
    private static String describe(Widget c) {
        StringBuilder text = new StringBuilder("id 0x").append(Integer.toHexString(c.getId())).append(" (")
                .append(component(c.getId())).append(")");
        if (c.getIndex() >= 0) {
            text.append(" [").append(c.getIndex()).append("]");
        }
        text.append("  parent ").append(c.getParentId() == -1 ? "none" : component(c.getParentId()));
        text.append("  hidden ").append(c.isHidden());
        Rectangle bounds = c.getBounds();
        text.append("  bounds ").append(bounds == null ? "?"
                : bounds.x + "," + bounds.y + " " + bounds.width + "×" + bounds.height);
        if (c.getItemId() >= 0) {
            text.append("  item ").append(c.getItemId()).append(" ×").append(c.getItemQuantity());
        }
        String[] actions = c.getActions();
        text.append("  actions ").append(actions == null ? "none" : String.join(", ",
                Arrays.stream(actions).filter(action -> action != null).toList()));
        return text.toString();
    }

    private static String component(int packedId) {
        return WidgetUtil.componentToInterface(packedId) + ":" + WidgetUtil.componentToId(packedId);
    }

    private static String item(Widget c) {
        return c.getItemId() < 0 ? "" : c.getItemId() + " ×" + c.getItemQuantity();
    }

    private static String ops(Widget c) {
        String[] actions = c.getActions();
        if (actions == null) {
            return "";
        }
        List<String> ops = new ArrayList<>();
        for (String op : actions) {
            if (op != null && !op.isEmpty()) {
                ops.add(op);
            }
        }
        return String.join(", ", ops);
    }

    @Override
    protected void paintOverlay(Snapshot snapshot, OverlayContext overlay) {
        snapshot.marks().paint(overlay);
    }

    @Override
    protected ToolView<Snapshot> createView() {
        return new InspectorView();
    }

    /**
     * A group picker over a table of the group's components, and the selected component's detail under it.
     */
    private final class InspectorView implements ToolView<Snapshot> {
        private final JComboBox<Integer> groups = new JComboBox<>();
        private final TableView<Row> table = TableView.<Row>builder("components")
                .column("Component", String.class, 170, Row::tree)
                .column("Type", String.class, 80, Row::type)
                .column("Parent", String.class, 90, Row::parent)
                .column("X", Integer.class, 46, Row::x)
                .column("Y", Integer.class, 46, Row::y)
                .column("W", Integer.class, 46, Row::width)
                .column("H", Integer.class, 46, Row::height)
                .column("Hidden", Boolean.class, 54, Row::hidden)
                .column("Drawn", Boolean.class, 54, Row::rendering)
                .column("Text", String.class, 160, Row::text)
                .column("Item", String.class, 90, Row::item)
                .column("Ops", String.class, 160, Row::ops)
                .key(Row::key)
                .onSelect(row -> selected = row == null ? null : row.key())
                .build();
        private final JLabel detail = new JLabel(" ");
        private final JPanel root = new JPanel(new BorderLayout());
        private List<Integer> shownGroups = List.of();
        private boolean filling;

        InspectorView() {
            groups.addActionListener(e -> {
                if (!filling && groups.getSelectedItem() instanceof Integer g) {
                    group = g;
                    selected = null;
                }
            });
            JLabel label = new JLabel("Group");
            label.setForeground(Theme.LIGHT);
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            bar.setBorder(new EmptyBorder(8, 4, 0, 8));
            bar.add(label);
            bar.add(groups);
            detail.setForeground(Theme.TEXT);
            detail.setBorder(new EmptyBorder(4, 8, 6, 8));
            root.add(bar, BorderLayout.NORTH);
            root.add(table.component(), BorderLayout.CENTER);
            root.add(detail, BorderLayout.SOUTH);
            root.setPreferredSize(new Dimension(980, 520));
        }

        @Override
        public JComponent component() {
            return root;
        }

        @Override
        public void update(Snapshot snapshot) {
            filling = true;
            try {
                if (!snapshot.groups().equals(shownGroups)) {
                    shownGroups = snapshot.groups();
                    groups.removeAllItems();
                    shownGroups.forEach(groups::addItem);
                }
                groups.setSelectedItem(snapshot.group());
            } finally {
                filling = false;
            }
            detail.setText(snapshot.detail() == null ? " " : snapshot.detail());
            table.update(snapshot.rows());
        }
    }
}
