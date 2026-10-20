package com.kraken.client.ui.devtools.tools;

import com.kraken.api.Client;
import com.kraken.api.KrakenApi;
import com.kraken.api.lua.IfGroup;
import com.kraken.api.lua.IfType;
import com.kraken.api.lua.InterfaceCoordinates;
import com.kraken.api.lua.InterfaceQuery;
import com.kraken.api.lua.Vector2;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetUtil;
import com.kraken.client.ui.devtools.Category;
import com.kraken.client.ui.devtools.DevTool;
import com.kraken.client.ui.devtools.ToolOption;
import com.kraken.client.ui.devtools.view.TableView;
import com.kraken.client.ui.devtools.view.ToolView;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.overlay.OverlayContext;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Browses one open interface group as a tree: its static components (nested by {@code layerId}) with their dynamic
 * children ({@code IfType.children}) under them. The selected component is outlined over the game, and optionally
 * every component the client drew this frame.
 *
 * <p>The RuneLite view adds, for the selected component only, what the RuneLite-shaped API reads for it
 * ({@code Client.getWidget}, then {@code Widget.getChild} down to a dynamic component): its id, index and parent id,
 * RuneLite's hidden rule (own flag, parents, open group) beside the Lua column's own flag, its canvas bounds, item and
 * actions.
 */
final class WidgetInspectorTool extends DevTool<WidgetInspectorTool.Snapshot> {
    private static final int MAX_ROWS = 5_000;
    private static final int DEFAULT_GROUP = 149;
    private static final Color SELECTED = new Color(0x3cdcff);
    private static final Color DRAWN = new Color(255, 255, 255, 90);

    /**
     * One component in tree order.
     *
     * @param key       the component's id, e.g. {@code 162:57[3]}
     * @param tree      the id indented by depth
     * @param type      the {@code IfTypes} name
     * @param x         x relative to the parent
     * @param y         y relative to the parent
     * @param width     the width
     * @param height    the height
     * @param hidden    hidden by itself
     * @param rendering drawn in the client's latest frame
     * @param text      the text, with markup
     * @param item      the item an inventory slot shows ("id ×count"), or ""
     * @param ops       the non-empty op names
     * @param screen    the top-left corner on screen, in client pixels
     * @param runelite  what the RuneLite-shaped API reads for the component; "" unless it is the selected one and the
     *                  RuneLite view is on
     */
    record Row(String key, String tree, String type, int x, int y, int width, int height, boolean hidden,
               boolean rendering, String text, String item, String ops, Vector2 screen, String runelite) {
    }

    /**
     * What the window and overlay show.
     *
     * @param groups the open groups
     * @param group  the group shown
     * @param rows   its components in tree order
     * @param marks  outlines over the game
     */
    record Snapshot(List<Integer> groups, int group, List<Row> rows, Marks marks) {
    }

    private final ToolOption allDrawn = option("drawn", "Outline everything drawn", false);
    private final ToolOption runelite = option("runelite", "RuneLite view", false);
    /**
     * Chosen in the window (EDT), read on the poll thread.
     */
    private volatile int group = -1;
    private volatile String selected;

    WidgetInspectorTool() {
        super("widgets", "Widget inspector", Category.INTERFACE, MaterialDesignW.WIDGETS_OUTLINE,
                "Browse an open interface's components as a tree (retrieveIfGroup, IfType.children) with their type, "
                        + "position, text, item and ops, and outline the selected one in game. The RuneLite view adds "
                        + "what the RuneLite-shaped Widget reads for the selected one.");
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
        InterfaceQuery interfaces = api.lua().interfaces();
        List<Integer> open = interfaces.getOpenGroups();
        if (open.isEmpty()) {
            return null;
        }
        int shown = group;
        if (!open.contains(shown)) {
            shown = open.contains(DEFAULT_GROUP) ? DEFAULT_GROUP : open.get(0);
        }
        IfGroup ifGroup = interfaces.retrieveIfGroup(shown);
        List<Row> rows = new ArrayList<>();
        Vector2 clientSize = api.lua().graphics().clientDimensions();
        Marks.Builder marks = clientSize == null ? null : new Marks.Builder(clientSize);
        if (ifGroup != null) {
            new Tree(ifGroup, rows, marks, runelite.isOn() ? api.client() : null).walk();
        }
        return new Snapshot(open, shown, List.copyOf(rows), marks == null ? Marks.NONE : marks.build());
    }

    /**
     * Lays a group out in tree order. Screen positions are summed on the way down, which is what
     * {@code IfType.getScreenCoords} does on the way up, without reading each parent again. Each component carries the
     * sub ids of the dynamic components from its static root down to it, which is how the RuneLite view finds the
     * {@code Widget}.
     */
    private final class Tree {
        private final IfGroup group;
        private final List<Row> rows;
        private final Marks.Builder marks;
        private final Client client;
        private final Map<Integer, List<IfType>> staticChildren = new HashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final String selectedKey = selected;

        /**
         * Prepares a walk.
         *
         * @param group  the group to lay out
         * @param rows   where the rows go
         * @param marks  where the outlines go, or null
         * @param client the RuneLite-shaped client for the selected component's RuneLite view, or null when it's off
         */
        Tree(IfGroup group, List<Row> rows, Marks.Builder marks, Client client) {
            this.group = group;
            this.rows = rows;
            this.marks = marks;
            this.client = client;
        }

        void walk() {
            List<IfType> top = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (IfType c : group.components()) {
                if (c != null) {
                    ids.add(c.getPackedId());
                }
            }
            for (IfType c : group.components()) {
                if (c == null) {
                    continue;
                }
                if (ids.contains(c.getLayerId())) {
                    staticChildren.computeIfAbsent(c.getLayerId(), k -> new ArrayList<>()).add(c);
                } else {
                    top.add(c);
                }
            }
            InterfaceCoordinates at = group.coordinates();
            Vector2 origin = new Vector2(at.offX() + at.scriptOffX(), at.offY() + at.scriptOffY());
            for (IfType c : top) {
                visit(c, 0, origin, List.of());
            }
        }

        /**
         * Adds a component's row, then its children's.
         *
         * @param c            the component
         * @param depth        how deep it is in the tree
         * @param parentScreen its parent's top-left corner on screen
         * @param path         the sub ids of the dynamic components from its static root down to it (empty for a
         *                     static component)
         */
        private void visit(IfType c, int depth, Vector2 parentScreen, List<Integer> path) {
            String key = c.toString();
            if (rows.size() >= MAX_ROWS || !seen.add(key)) {
                return;
            }
            Vector2 screen = new Vector2(parentScreen.x() + c.getX(), parentScreen.y() + c.getY());
            boolean rendering = c.isRendering();
            String rl = client != null && key.equals(selectedKey) ? runeLite(c, path) : "";
            rows.add(new Row(key, "  ".repeat(depth) + key, IfType.typeName(c.getType()), c.getX(), c.getY(),
                    c.getWidth(), c.getHeight(), c.isHidden(), rendering, c.getText(), item(c), ops(c), screen, rl));
            mark(c, key, screen, rendering);
            if (c.isStatic()) {
                for (IfType child : staticChildren.getOrDefault(c.getPackedId(), List.of())) {
                    visit(child, depth + 1, screen, List.of());
                }
            }
            for (IfType child : c.getChildren()) {
                List<Integer> childPath = new ArrayList<>(path);
                childPath.add(child.getSubId());
                visit(child, depth + 1, screen, childPath);
            }
        }

        /**
         * What the RuneLite-shaped API reads for a component: {@code getWidget} on its static root, then
         * {@code getChild} down the dynamic components to it.
         *
         * @param c    the component
         * @param path the sub ids from its static root down to it
         * @return the RuneLite view's text, or "?" if the widget can't be found
         */
        private String runeLite(IfType c, List<Integer> path) {
            Widget widget = client.getWidget(c.getPackedId());
            for (int subId : path) {
                widget = widget == null ? null : widget.getChild(subId);
            }
            if (widget == null) {
                return "?";
            }
            int id = widget.getId();
            StringBuilder text = new StringBuilder("id 0x").append(Integer.toHexString(id)).append(" (")
                    .append(component(id)).append(")");
            if (widget.getIndex() >= 0) {
                text.append(" [").append(widget.getIndex()).append("]");
            }
            text.append("  parent ").append(widget.getParentId() == -1 ? "none" : component(widget.getParentId()));
            text.append("  hidden ").append(widget.isHidden());
            Rectangle bounds = widget.getBounds();
            text.append("  bounds ").append(bounds == null ? "?"
                    : bounds.x + "," + bounds.y + " " + bounds.width + "×" + bounds.height);
            if (widget.getItemId() >= 0) {
                text.append("  item ").append(widget.getItemId()).append(" ×").append(widget.getItemQuantity());
            }
            String[] actions = widget.getActions();
            text.append("  actions ").append(actions == null ? "none" : String.join(", ",
                    Arrays.stream(actions).filter(action -> action != null).toList()));
            return text.toString();
        }

        private void mark(IfType c, String key, Vector2 screen, boolean rendering) {
            if (marks == null) {
                return;
            }
            boolean isSelected = key.equals(selectedKey);
            if (!isSelected && !(rendering && allDrawn.isOn())) {
                return;
            }
            float x = screen.x();
            float y = screen.y();
            Vector2[] box = {new Vector2(x, y), new Vector2(x + c.getWidth(), y),
                    new Vector2(x + c.getWidth(), y + c.getHeight()), new Vector2(x, y + c.getHeight())};
            marks.outline(box, isSelected ? SELECTED : DRAWN, null);
            if (isSelected) {
                marks.label(key, new Vector2(x + c.getWidth() / 2f, y - 6), SELECTED);
            }
        }
    }

    private static String component(int packedId) {
        return WidgetUtil.componentToInterface(packedId) + ":" + WidgetUtil.componentToId(packedId);
    }

    private static String item(IfType c) {
        return c.getLinkObjType() < 0 ? "" : c.getLinkObjType() + " ×" + c.getLinkObjCount();
    }

    private static String ops(IfType c) {
        List<String> ops = new ArrayList<>();
        for (String op : c.getOpNames()) {
            if (!op.isEmpty()) {
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
     * A group picker over a table of the group's components.
     */
    private final class InspectorView implements ToolView<Snapshot> {
        private final JComboBox<Integer> groups = new JComboBox<>();
        private final TableView<Row> table = TableView.<Row>builder("components")
                .column("Component", String.class, 170, Row::tree)
                .column("Type", String.class, 80, Row::type)
                .column("X", Integer.class, 46, Row::x)
                .column("Y", Integer.class, 46, Row::y)
                .column("W", Integer.class, 46, Row::width)
                .column("H", Integer.class, 46, Row::height)
                .column("Hidden", Boolean.class, 54, Row::hidden)
                .column("Drawn", Boolean.class, 54, Row::rendering)
                .column("Text", String.class, 160, Row::text)
                .column("Item", String.class, 90, Row::item)
                .column("Ops", String.class, 160, Row::ops)
                .column("RuneLite", String.class, 420, Row::runelite)
                .key(Row::key)
                .onSelect(row -> selected = row == null ? null : row.key())
                .build();
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
            root.add(bar, BorderLayout.NORTH);
            root.add(table.component(), BorderLayout.CENTER);
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
            table.update(snapshot.rows());
        }
    }
}
