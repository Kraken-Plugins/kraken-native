package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Filtered;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import com.kraken.client.ui.devtools.tools.WidgetForest.WidgetNode;
import com.kraken.client.ui.devtools.view.GamePicker;
import com.kraken.client.ui.devtools.view.ToolView;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignH;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import static com.formdev.flatlaf.FlatClientProperties.*;

/**
 * The widget inspector's window: a bar to pick a widget in the game, search and filter, over the tree of open
 * interfaces with the selected node's detail beside it.
 *
 * <p>The tree keeps its rows as the game changes: each new forest is merged into the rows already shown by key, so
 * expanded rows stay expanded and the selection stays put. A row is dimmed when the client didn't draw it.
 */
final class WidgetInspectorView implements ToolView<WidgetInspectorTool.Snapshot> {

    /**
     * How many search matches the tree opens up to.
     */
    private static final int MAX_EXPANDED_MATCHES = 400;

    /**
     * How many rows "Expand all below" opens.
     */
    private static final int MAX_EXPAND_ALL = 3_000;

    private static final Color ID_TEXT = new Color(0x8a8a8a);
    private static final Color SUMMARY_TEXT = new Color(0x8c8c8c);
    private static final Color ROW_HOVER = new Color(255, 255, 255, 12);
    private static final Color PANEL = new Color(20, 20, 20, 228);
    private static final Color PANEL_EDGE = new Color(255, 255, 255, 40);
    private static final float DIM = 0.42f;

    private final WidgetInspectorTool tool;
    private final WidgetNames names = WidgetNames.get();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(rootNode);
    private final Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final JTree tree = new InspectorTree();
    private final JTextField search = new JTextField();
    private final JToggleButton pick = new JToggleButton("Pick",
            FontIcon.of(MaterialDesignC.CROSSHAIRS_GPS, 16, Theme.TEXT));
    private final JToggleButton onScreen = toolbarToggle(MaterialDesignE.EYE_CHECK_OUTLINE,
            "Only what is on screen: hide widgets that are hidden or weren't drawn");
    private final JToggleButton inGame = toolbarToggle(MaterialDesignV.VECTOR_SQUARE,
            "Outline the selection in the game");
    private final JLabel count = new JLabel();
    private final WidgetDetailPanel detail = new WidgetDetailPanel(this::selectKey);
    private final JPanel root = new JPanel(new BorderLayout());

    private WidgetForest forest = WidgetForest.EMPTY;
    private WidgetDetail latestDetail;
    private Set<String> matches = Set.of();
    private String selectedKey;
    private String lastQuery = "";
    private int hoverRow = -1;
    private boolean syncing;
    private boolean drawnKnown;

    private GamePicker picker;
    private Point pickAt;
    private List<Row> pickStack = List.of();
    private int pickIndex;

    WidgetInspectorView(WidgetInspectorTool tool) {
        this.tool = tool;
        // A window opened again shows what the tool still has selected
        this.selectedKey = tool.selected();
        buildTree();

        search.putClientProperty(PLACEHOLDER_TEXT, "Search names, ids, text and actions");
        search.putClientProperty(TEXT_FIELD_LEADING_ICON, FontIcon.of(MaterialDesignM.MAGNIFY, 16, Theme.LIGHT));
        search.putClientProperty(TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        search.addActionListener(e -> selectFirstMatch());

        pick.setToolTipText("<html>Click a widget in the game to select it.<br>Scroll to step through the widgets "
                + "under the pointer; Shift-click keeps picking; right-click or Esc stops.</html>");
        pick.setFocusable(false);
        pick.addActionListener(e -> setPicking(pick.isSelected()));
        onScreen.addActionListener(e -> applyFilter());
        inGame.addActionListener(e -> tool.showInGame(inGame.isSelected()));
        JButton collapse = toolbarButton(MaterialDesignU.UNFOLD_LESS_HORIZONTAL, "Collapse all", e -> collapseAll());
        count.setForeground(Theme.LIGHT);
        count.setBorder(new EmptyBorder(0, 6, 0, 0));

        Box tools = Box.createHorizontalBox();
        tools.add(onScreen);
        tools.add(Box.createHorizontalStrut(2));
        tools.add(inGame);
        tools.add(Box.createHorizontalStrut(2));
        tools.add(collapse);
        tools.add(count);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new EmptyBorder(8, 8, 8, 10));
        bar.add(pick, BorderLayout.WEST);
        bar.add(search, BorderLayout.CENTER);
        bar.add(tools, BorderLayout.EAST);

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.getViewport().setBackground(Theme.DARKER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailScroll);
        split.setResizeWeight(0.58);
        split.setDividerSize(5);
        split.setBorder(new MatteBorder(1, 0, 0, 0, Theme.DARKEST));
        split.setContinuousLayout(true);

        root.add(bar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(1040, 640));
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.58));

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "search");
        root.getActionMap().put("search", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                search.requestFocusInWindow();
                search.selectAll();
            }
        });
        // Esc stops picking before it reaches the window, which would close
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stopPicking");
        root.getActionMap().put("stopPicking", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setPicking(false);
            }

            @Override
            public boolean isEnabled() {
                return picker != null && picker.isActive();
            }
        });
        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !root.isDisplayable()) {
                setPicking(false);
                tool.hover(null);
            }
        });
        detail.show(List.of(), null, names);
        updateCount();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void update(WidgetInspectorTool.Snapshot snapshot) {
        inGame.setSelected(snapshot.overlayOn());
        if (snapshot.forest() != forest) {
            forest = snapshot.forest();
            drawnKnown = !forest.drawn().isEmpty();
            applyFilter();
            showDetail();
            if (picker != null && picker.isActive()) {
                repick();
                picker.repaint();
            }
        }
        WidgetDetail fresh = snapshot.detail();
        if (fresh != null && fresh.key().equals(selectedKey) && !fresh.equals(latestDetail)) {
            latestDetail = fresh;
            showDetail();
        }
    }

    private void buildTree() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setLargeModel(true);
        tree.setCellRenderer(new Cell());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBorder(new EmptyBorder(4, 2, 4, 2));
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(e -> {
            if (syncing) {
                return;
            }
            Row row = row(tree.getSelectionPath());
            selectedKey = row == null ? null : row.key();
            tool.select(selectedKey);
            showDetail();
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = rowAt(e.getY());
                if (row != hoverRow) {
                    repaintRow(hoverRow);
                    hoverRow = row;
                    repaintRow(row);
                    Row hovered = row < 0 ? null : row(tree.getPathForRow(row));
                    tool.hover(hovered == null ? null : hovered.key());
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                repaintRow(hoverRow);
                hoverRow = -1;
                tool.hover(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        };
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);
        tree.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copy(WidgetForest.code(forest.path(selectedKey), names));
            }
        });
    }

    /**
     * Shows the tree's context menu on a right-click.
     *
     * @param e the mouse event
     */
    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = rowAt(e.getY());
        if (index < 0) {
            return;
        }
        TreePath path = tree.getPathForRow(index);
        tree.setSelectionPath(path);
        Row row = row(path);
        if (row == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Copy code", MaterialDesignC.CODE_TAGS,
                () -> copy(WidgetForest.code(forest.path(row.key()), names))));
        menu.add(menuItem("Copy id", MaterialDesignC.CONTENT_COPY, () -> copy(row.isGroup()
                ? Integer.toString(row.id()) : row.key())));
        if (!row.name().isEmpty()) {
            menu.add(menuItem("Copy name", MaterialDesignF.FORMAT_LETTER_CASE, () -> copy(row.name())));
        }
        menu.addSeparator();
        menu.add(menuItem("Expand all below", MaterialDesignU.UNFOLD_MORE_HORIZONTAL, () -> expandAll(path)));
        menu.add(menuItem("Collapse all below", MaterialDesignU.UNFOLD_LESS_HORIZONTAL, () -> collapseBelow(path)));
        menu.show(tree, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(String text, Ikon icon, Runnable action) {
        JMenuItem item = new JMenuItem(text, FontIcon.of(icon, 16, Theme.LIGHT));
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Shows the forest through the search and the on-screen filter, merged into the rows already shown.
     */
    private void applyFilter() {
        String query = search.getText().trim();
        Filtered filtered = forest.filter(query, onScreen.isSelected());
        matches = filtered.matches();
        boolean first = rootNode.getChildCount() == 0;
        syncing = true;
        try {
            reconcile(rootNode, filtered.roots());
            nodes.clear();
            index(rootNode);
            if (first) {
                expandTop();
            }
            if (!query.equals(lastQuery)) {
                if (query.isEmpty()) {
                    collapseAll();
                    expandToSelection();
                } else {
                    expandToMatches();
                }
                lastQuery = query;
            }
            restoreSelection();
        } finally {
            syncing = false;
        }
        updateCount();
    }

    /**
     * Merges a list of nodes into a tree node's children: rows that stay keep their tree node (and so their expanded
     * state), rows that go are removed, new rows are inserted, and changed rows are redrawn.
     *
     * @param parent the tree node
     * @param next   the nodes its children should show, in order
     */
    private void reconcile(DefaultMutableTreeNode parent, List<WidgetNode> next) {
        Set<DefaultMutableTreeNode> fresh = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!sameKeys(parent, next)) {
            restructure(parent, next, fresh);
        }
        for (int i = 0; i < next.size(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (fresh.contains(child)) {
                continue;
            }
            WidgetNode node = next.get(i);
            if (!node.row().equals(child.getUserObject())) {
                child.setUserObject(node.row());
                model.nodeChanged(child);
            }
            reconcile(child, node.children());
        }
    }

    private static boolean sameKeys(DefaultMutableTreeNode parent, List<WidgetNode> next) {
        if (parent.getChildCount() != next.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            if (!next.get(i).row().key().equals(key(parent.getChildAt(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Brings a tree node's children to a new list of keys: removes the ones that went, then inserts the new ones
     * (built whole, with their own children) where they belong. If the rows that stay changed order, the children are
     * replaced and the expanded rows under them opened again.
     *
     * @param parent the tree node
     * @param next   the nodes its children should show
     * @param fresh  receives the tree nodes built here, which need no merging
     */
    private void restructure(DefaultMutableTreeNode parent, List<WidgetNode> next, Set<DefaultMutableTreeNode> fresh) {
        Set<String> keep = new HashSet<>();
        for (WidgetNode node : next) {
            keep.add(node.row().key());
        }
        List<Integer> removedAt = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (!keep.contains(key(parent.getChildAt(i)))) {
                removedAt.add(i);
                removed.add(parent.getChildAt(i));
            }
        }
        for (int i = removedAt.size() - 1; i >= 0; i--) {
            parent.remove((int) removedAt.get(i));
        }
        if (!removedAt.isEmpty()) {
            model.nodesWereRemoved(parent, removedAt.stream().mapToInt(Integer::intValue).toArray(), removed.toArray());
        }

        Map<String, DefaultMutableTreeNode> old = new HashMap<>();
        List<String> oldOrder = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            old.put(key(child), child);
            oldOrder.add(key(child));
        }
        List<String> keptOrder = next.stream().map(node -> node.row().key()).filter(old::containsKey).toList();
        if (!keptOrder.equals(oldOrder)) {
            List<String> expanded = expandedBelow(parent);
            parent.removeAllChildren();
            for (WidgetNode node : next) {
                DefaultMutableTreeNode child = old.get(node.row().key());
                if (child == null) {
                    child = build(node);
                    fresh.add(child);
                }
                parent.add(child);
            }
            model.nodeStructureChanged(parent);
            nodes.clear();
            index(rootNode);
            for (String key : expanded) {
                DefaultMutableTreeNode node = nodes.get(key);
                if (node != null) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
            return;
        }
        List<Integer> insertedAt = new ArrayList<>();
        for (int i = 0; i < next.size(); i++) {
            WidgetNode node = next.get(i);
            if (!old.containsKey(node.row().key())) {
                DefaultMutableTreeNode child = build(node);
                fresh.add(child);
                parent.insert(child, i);
                insertedAt.add(i);
            }
        }
        if (!insertedAt.isEmpty()) {
            model.nodesWereInserted(parent, insertedAt.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /**
     * A tree node for a forest node, with tree nodes for all its children.
     *
     * @param node the forest node
     * @return the tree node
     */
    private static DefaultMutableTreeNode build(WidgetNode node) {
        DefaultMutableTreeNode tree = new DefaultMutableTreeNode(node.row());
        for (WidgetNode child : node.children()) {
            tree.add(build(child));
        }
        return tree;
    }

    /**
     * The keys of the expanded rows under a tree node.
     *
     * @param parent the tree node
     * @return the keys
     */
    private List<String> expandedBelow(DefaultMutableTreeNode parent) {
        List<String> keys = new ArrayList<>();
        Enumeration<TreePath> open = tree.getExpandedDescendants(new TreePath(parent.getPath()));
        while (open != null && open.hasMoreElements()) {
            Row row = row(open.nextElement());
            if (row != null) {
                keys.add(row.key());
            }
        }
        return keys;
    }

    private void index(DefaultMutableTreeNode node) {
        if (node.getUserObject() instanceof Row row) {
            nodes.put(row.key(), node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            index((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    private static String key(TreeNode node) {
        return ((DefaultMutableTreeNode) node).getUserObject() instanceof Row row ? row.key() : "";
    }

    private static Row row(TreePath path) {
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof Row row ? row : null;
    }

    /**
     * Puts the tree's selection back on the selected node after the rows changed, without telling the tool.
     */
    private void restoreSelection() {
        DefaultMutableTreeNode node = selectedKey == null ? null : nodes.get(selectedKey);
        if (node == null) {
            tree.clearSelection();
            return;
        }
        TreePath path = new TreePath(node.getPath());
        if (!path.equals(tree.getSelectionPath())) {
            tree.setSelectionPath(path);
        }
    }

    /**
     * Selects a node, from a pick or a breadcrumb: shows it in the tree (clearing the search if that hides it),
     * scrolls to it and reads its fields.
     *
     * @param key the node's key
     */
    void selectKey(String key) {
        if (key == null) {
            return;
        }
        if (!nodes.containsKey(key) && (!search.getText().isEmpty() || onScreen.isSelected())) {
            onScreen.setSelected(false);
            if (search.getText().isEmpty()) {
                applyFilter();
            } else {
                // Its listener shows the whole forest again
                search.setText("");
            }
        }
        selectedKey = key;
        tool.select(key);
        latestDetail = null;
        DefaultMutableTreeNode node = nodes.get(key);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            syncing = true;
            try {
                tree.makeVisible(path);
                tree.setSelectionPath(path);
            } finally {
                syncing = false;
            }
            scrollTo(path);
        }
        showDetail();
    }

    /**
     * Scrolls a row into the middle of the view without scrolling across.
     *
     * @param path the row's path
     */
    private void scrollTo(TreePath path) {
        Rectangle bounds = tree.getPathBounds(path);
        if (bounds == null) {
            return;
        }
        Rectangle visible = tree.getVisibleRect();
        int top = Math.max(0, bounds.y - (visible.height - bounds.height) / 2);
        tree.scrollRectToVisible(new Rectangle(visible.x, top, visible.width, visible.height));
    }

    private void selectFirstMatch() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            Row r = row(tree.getPathForRow(row));
            if (r != null && matches.contains(r.key())) {
                selectKey(r.key());
                return;
            }
        }
        Enumeration<TreeNode> all = rootNode.preorderEnumeration();
        while (all.hasMoreElements()) {
            String key = key(all.nextElement());
            if (matches.contains(key)) {
                selectKey(key);
                return;
            }
        }
    }

    private void expandToMatches() {
        int opened = 0;
        Enumeration<TreeNode> all = rootNode.preorderEnumeration();
        while (all.hasMoreElements() && opened < MAX_EXPANDED_MATCHES) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) all.nextElement();
            if (matches.contains(key(node))) {
                tree.makeVisible(new TreePath(node.getPath()));
                opened++;
            }
        }
    }

    private void expandToSelection() {
        DefaultMutableTreeNode node = selectedKey == null ? null : nodes.get(selectedKey);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            tree.makeVisible(path);
            scrollTo(path);
        }
    }

    private void collapseAll() {
        for (int row = tree.getRowCount() - 1; row >= 0; row--) {
            tree.collapseRow(row);
        }
        expandTop();
    }

    /**
     * Opens the roots and their children, so the first rows show the top of each interface.
     */
    private void expandTop() {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            tree.expandPath(new TreePath(root.getPath()));
            for (int j = 0; j < root.getChildCount(); j++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(j);
                if (!child.isLeaf()) {
                    tree.expandPath(new TreePath(child.getPath()));
                }
            }
        }
    }

    private void expandAll(TreePath path) {
        int opened = 0;
        Enumeration<TreeNode> below = ((DefaultMutableTreeNode) path.getLastPathComponent()).preorderEnumeration();
        while (below.hasMoreElements() && opened < MAX_EXPAND_ALL) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) below.nextElement();
            if (!node.isLeaf()) {
                tree.expandPath(new TreePath(node.getPath()));
                opened += node.getChildCount();
            }
        }
    }

    private void collapseBelow(TreePath path) {
        Enumeration<TreeNode> below = ((DefaultMutableTreeNode) path.getLastPathComponent()).depthFirstEnumeration();
        while (below.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) below.nextElement();
            if (!node.isLeaf()) {
                tree.collapsePath(new TreePath(node.getPath()));
            }
        }
    }

    private void showDetail() {
        List<Row> path = selectedKey == null ? List.of() : forest.path(selectedKey);
        WidgetDetail fields = latestDetail != null && latestDetail.key().equals(selectedKey) ? latestDetail : null;
        if (path.isEmpty() && fields == null && selectedKey != null) {
            fields = new WidgetDetail(selectedKey, null, true, List.of());
        }
        detail.show(path, fields, names);
    }

    private void updateCount() {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.ROOT);
        if (!search.getText().trim().isEmpty()) {
            count.setText(matches.isEmpty() ? "No matches" : format.format(matches.size())
                    + (matches.size() == 1 ? " match" : " matches"));
            count.setForeground(matches.isEmpty() ? Theme.WARNING : Theme.LIGHT);
            return;
        }
        count.setForeground(Theme.LIGHT);
        count.setText(forest.groupCount() == 0 ? "No interfaces open"
                : format.format(forest.getWidgetCount()) + " widgets in " + forest.groupCount() + " interfaces");
    }

    private int rowAt(int y) {
        int row = tree.getClosestRowForLocation(0, y);
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? row : -1;
    }

    private void repaintRow(int row) {
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds != null) {
            tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }

    private static void copy(String text) {
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }

    /**
     * Turns picking on or off.
     *
     * @param on true to lay the picker over the game
     */
    private void setPicking(boolean on) {
        if (on) {
            Component area = tool.gameArea();
            if (picker == null && area != null) {
                picker = new GamePicker(area, new PickHandler());
            }
            if (picker != null && picker.start()) {
                tool.requestRead();
                pickAt = null;
                pickStack = List.of();
            }
        } else if (picker != null) {
            picker.stop();
            pickAt = null;
            pickStack = List.of();
        }
        pick.setSelected(picker != null && picker.isActive());
    }

    /**
     * Finds the widgets under the pointer again, staying on the one shown if it is still there.
     */
    private void repick() {
        Row current = pickStack.isEmpty() ? null : pickStack.get(pickIndex);
        Dimension canvas = forest.getCanvas();
        Component area = tool.gameArea();
        if (pickAt == null || area == null || area.getWidth() <= 0 || area.getHeight() <= 0) {
            pickStack = List.of();
            pickIndex = 0;
            return;
        }
        int x = (int) Math.floor(pickAt.x * canvas.width / (double) area.getWidth());
        int y = (int) Math.floor(pickAt.y * canvas.height / (double) area.getHeight());
        pickStack = forest.pick(x, y);
        pickIndex = 0;
        if (current != null) {
            for (int i = 0; i < pickStack.size(); i++) {
                if (pickStack.get(i).key().equals(current.key())) {
                    pickIndex = i;
                    break;
                }
            }
        }
    }

    /**
     * Hears the picker: the pointer, clicks, the wheel and Esc, and draws the widget under the pointer.
     */
    private final class PickHandler implements GamePicker.Handler {
        @Override
        public void moved(Point at) {
            pickAt = at;
            if (at != null) {
                Row before = pickStack.isEmpty() ? null : pickStack.get(pickIndex);
                repick();
                if (before != null && !pickStack.isEmpty() && !pickStack.get(pickIndex).key().equals(before.key())) {
                    pickIndex = 0;
                }
            } else {
                pickStack = List.of();
            }
            picker.repaint();
        }

        @Override
        public void pressed(Point at, MouseEvent event) {
            if (SwingUtilities.isRightMouseButton(event)) {
                setPicking(false);
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            if (pickAt == null || !pickAt.equals(at)) {
                moved(at);
            }
            if (!pickStack.isEmpty()) {
                selectKey(pickStack.get(pickIndex).key());
            }
            if (!event.isShiftDown()) {
                setPicking(false);
            } else {
                picker.repaint();
            }
        }

        @Override
        public void scrolled(int steps) {
            if (!pickStack.isEmpty()) {
                pickIndex = Math.floorMod(pickIndex + steps, pickStack.size());
                picker.repaint();
            }
        }

        @Override
        public void cancelled() {
            setPicking(false);
        }

        @Override
        public void paint(Graphics2D g, int width, int height) {
            paintPick(g, width, height);
        }
    }

    /**
     * Draws the picker's layer: the other widgets under the pointer faintly, the one a click picks with its name, and
     * a hint bar.
     *
     * @param g      the graphics, in the game area's UI pixels
     * @param width  the game area's width
     * @param height the game area's height
     */
    private void paintPick(Graphics2D g, int width, int height) {
        Dimension canvas = forest.getCanvas();
        double sx = width / (double) canvas.width;
        double sy = height / (double) canvas.height;
        Row current = pickStack.isEmpty() ? null : pickStack.get(pickIndex);

        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
        g.setColor(new Color(255, 255, 255, 80));
        long canvasArea = (long) canvas.width * canvas.height;
        for (Row row : pickStack) {
            // Layers as big as the screen would only trace its edge
            Area shown = row.visible();
            if (row != current && shown != null && (long) shown.width() * shown.height() * 2 < canvasArea) {
                g.draw(rect(shown, sx, sy));
            }
        }
        if (current != null) {
            Rectangle2D box = rect(current.bounds(), sx, sy);
            Rectangle2D shown = rect(current.visible(), sx, sy);
            g.setColor(new Color(0x3c, 0xdc, 0xff, 40));
            g.fill(shown);
            g.setStroke(new BasicStroke(2f));
            g.setColor(WidgetInspectorTool.SELECTED);
            g.draw(shown);
            if (!box.equals(shown)) {
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                        new float[]{2f, 3f}, 0f));
                g.draw(box);
            }
            paintPickLabel(g, current, shown, width, height);
        }
        String hint = pickStack.size() > 1
                ? "Click to inspect   ·   Scroll: " + (pickIndex + 1) + " of " + pickStack.size()
                + " here   ·   Shift-click keeps picking   ·   Esc stops"
                : "Click a widget to inspect it   ·   Shift-click keeps picking   ·   Right-click or Esc stops";
        paintHint(g, hint, width);
    }

    private static Rectangle2D rect(Area area, double sx, double sy) {
        if (area == null) {
            return new Rectangle2D.Double();
        }
        return new Rectangle2D.Double(area.x() * sx, area.y() * sy, area.width() * sx, area.height() * sy);
    }

    /**
     * Draws the picked widget's name and id in a box beside it.
     *
     * @param g      the graphics
     * @param row    the widget
     * @param box    where it is, in UI pixels
     * @param width  the game area's width
     * @param height the game area's height
     */
    private void paintPickLabel(Graphics2D g, Row row, Rectangle2D box, int width, int height) {
        String title = WidgetInspectorTool.title(forest.path(row.key()));
        String line1 = row.summary().isEmpty() ? title : title + "   " + row.summary();
        String line2 = row.key() + "   ·   " + WidgetType.name(row.type()) + "   ·   " + row.bounds().width() + " × "
                + row.bounds().height();
        Font titleFont = UIManager.getFont("Label.font").deriveFont(Font.BOLD);
        Font monoFont = mono(UIManager.getFont("Label.font").getSize2D() - 1f);
        FontMetrics titleMetrics = g.getFontMetrics(titleFont);
        FontMetrics monoMetrics = g.getFontMetrics(monoFont);
        line1 = clip(line1, titleMetrics, Math.max(120, width - 40));
        int pad = 8;
        int w = Math.max(titleMetrics.stringWidth(line1), monoMetrics.stringWidth(line2)) + 2 * pad + 14;
        int h = titleMetrics.getHeight() + monoMetrics.getHeight() + 2 * pad - 2;
        int x = (int) Math.round(Math.min(Math.max(4, box.getX()), width - w - 4.0));
        int y = (int) Math.round(box.getY() - h - 6);
        if (y < 30) {
            y = (int) Math.round(Math.min(box.getMaxY() + 6, height - h - 4.0));
        }
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 10, 10));
        g.setColor(PANEL_EDGE);
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, 10, 10));
        g.setColor(typeColor(row.type()));
        g.fill(new RoundRectangle2D.Float(x + pad, y + pad + 3, 6, titleMetrics.getAscent() - 4, 3, 3));
        g.setFont(titleFont);
        g.setColor(Theme.TEXT);
        g.drawString(line1, x + pad + 14, y + pad + titleMetrics.getAscent());
        g.setFont(monoFont);
        g.setColor(Theme.LIGHT);
        g.drawString(line2, x + pad + 14, y + pad + titleMetrics.getHeight() + monoMetrics.getAscent() - 2);
    }

    private static void paintHint(Graphics2D g, String hint, int width) {
        Font font = UIManager.getFont("Label.font");
        FontMetrics metrics = g.getFontMetrics(font);
        int w = metrics.stringWidth(hint) + 28;
        int h = metrics.getHeight() + 10;
        int x = (width - w) / 2;
        int y = 10;
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
        g.setColor(PANEL_EDGE);
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, h, h));
        FontIcon icon = FontIcon.of(MaterialDesignC.CROSSHAIRS_GPS, 14, WidgetInspectorTool.SELECTED);
        icon.paintIcon(null, g, x + 9, y + (h - icon.getIconHeight()) / 2);
        g.setFont(font);
        g.setColor(Theme.TEXT);
        g.drawString(hint, x + 20 + 4, y + 5 + metrics.getAscent());
    }

    private static String clip(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /**
     * The colour a widget type is drawn in: its icon, its chip and its mark in the picker.
     *
     * @param type the {@link WidgetType}, or -1 for a group
     * @return the colour
     */
    static Color typeColor(int type) {
        return switch (type) {
            case -1 -> Theme.ACCENT;
            case WidgetType.LAYER -> new Color(0x9aa5b4);
            case WidgetType.TEXT -> new Color(0x6cb6ff);
            case WidgetType.GRAPHIC -> new Color(0xe5b45a);
            case WidgetType.MODEL -> new Color(0xc58af9);
            case WidgetType.RECTANGLE -> new Color(0x4fd1c5);
            case WidgetType.LINE, WidgetType.CIRCLE -> new Color(0xf38ba8);
            case WidgetType.TEXT_INVENTORY -> new Color(0x9ece6a);
            case WidgetType.INPUT_BOX, WidgetType.INPUT_FIELD -> new Color(0x7dcfff);
            default -> Theme.LIGHT;
        };
    }

    /**
     * The icon a widget type is shown with.
     *
     * @param type the {@link WidgetType}, or -1 for a group
     * @return the icon
     */
    static Ikon typeIcon(int type) {
        return switch (type) {
            case -1 -> MaterialDesignA.APPLICATION;
            case WidgetType.LAYER -> MaterialDesignL.LAYERS_OUTLINE;
            case WidgetType.TEXT -> MaterialDesignF.FORMAT_TEXT;
            case WidgetType.GRAPHIC -> MaterialDesignI.IMAGE_OUTLINE;
            case WidgetType.MODEL -> MaterialDesignC.CUBE_OUTLINE;
            case WidgetType.RECTANGLE -> MaterialDesignS.SQUARE_OUTLINE;
            case WidgetType.LINE -> MaterialDesignV.VECTOR_LINE;
            case WidgetType.CIRCLE -> MaterialDesignC.CIRCLE_OUTLINE;
            case WidgetType.TEXT_INVENTORY -> MaterialDesignV.VIEW_GRID_OUTLINE;
            case WidgetType.INPUT_BOX, WidgetType.INPUT_FIELD -> MaterialDesignF.FORM_TEXTBOX;
            case WidgetType.IF1_TOOLTIP -> MaterialDesignT.TOOLTIP_OUTLINE;
            case WidgetType.CRM_VIEW -> MaterialDesignW.WEB;
            default -> MaterialDesignH.HELP_CIRCLE_OUTLINE;
        };
    }

    /**
     * The monospaced font at a size.
     *
     * @param size the point size
     * @return the font
     */
    static Font mono(float size) {
        Font font = UIManager.getFont("monospaced.font");
        return (font != null ? font : new Font(Font.MONOSPACED, Font.PLAIN, 12)).deriveFont(size);
    }

    private static JToggleButton toolbarToggle(Ikon icon, String tooltip) {
        JToggleButton button = new JToggleButton(FontIcon.of(icon, 16, Theme.LIGHT));
        button.setSelectedIcon(FontIcon.of(icon, 16, Theme.ACCENT));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }

    private static JButton toolbarButton(Ikon icon, String tooltip, java.awt.event.ActionListener action) {
        JButton button = new JButton(FontIcon.of(icon, 16, Theme.LIGHT));
        button.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }

    /**
     * The tree, with a faint band behind the row under the pointer and a tooltip per row.
     */
    private final class InspectorTree extends JTree {
        InspectorTree() {
            super(model);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Rectangle bounds = hoverRow < 0 ? null : getRowBounds(hoverRow);
            if (bounds != null) {
                g.setColor(ROW_HOVER);
                g.fillRect(0, bounds.y, getWidth(), bounds.height);
            }
            if (getRowCount() == 0) {
                String text = forest.groupCount() == 0 ? "No interfaces open"
                        : search.getText().isBlank() ? "Nothing on screen" : "No matches";
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(Theme.LIGHT);
                Rectangle visible = getVisibleRect();
                FontMetrics metrics = g2.getFontMetrics();
                g2.drawString(text, visible.x + (visible.width - metrics.stringWidth(text)) / 2,
                        visible.y + visible.height / 2);
            }
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = rowAt(event.getY());
            Row row = index < 0 ? null : row(getPathForRow(index));
            if (row == null) {
                return null;
            }
            StringBuilder html = new StringBuilder("<html>");
            if (row.isGroup()) {
                html.append("<b>Interface ").append(row.id()).append("</b>");
                if (!row.name().isEmpty()) {
                    html.append("&nbsp; ").append(Theme.escape(row.name()));
                }
                WidgetForest.GroupInfo info = forest.groupInfo(row.id());
                WidgetNode mount = info == null ? null : forest.node(info.mount());
                html.append("<br>").append(mount == null ? "Not drawn inside another interface"
                        : "Drawn inside " + Theme.escape(mount.row().key()) + " (found by where it is drawn)");
            } else {
                WidgetNames.Name name = row.kind() == Kind.STATIC ? names.component(row.id()) : null;
                html.append("<b>").append(Theme.escape(row.key())).append("</b>&nbsp; ")
                        .append(WidgetType.name(row.type()));
                if (name != null) {
                    html.append("<br>").append(Theme.escape(name.constant()));
                }
                if (!row.summary().isEmpty()) {
                    html.append("<br>").append(Theme.escape(row.summary()));
                }
                Area bounds = row.bounds();
                if (bounds != null) {
                    html.append("<br>").append(bounds.x()).append(", ").append(bounds.y()).append("&nbsp; ")
                            .append(bounds.width()).append(" × ").append(bounds.height());
                }
                String state = row.selfHidden() ? "Hidden by its own flag" : row.hidden() ? "Hidden by a parent"
                        : !row.drawn() && drawnKnown ? "Not drawn in the latest frame" : null;
                if (state != null) {
                    html.append("<br><i>").append(state).append("</i>");
                }
            }
            return html.append("</html>").toString();
        }
    }

    /**
     * Paints a tree row: the type's icon, the id, the name, a summary of what the widget shows, and an eye if it hides
     * itself. Rows the client didn't draw are dimmed.
     */
    private final class Cell extends JComponent implements TreeCellRenderer {
        private static final int ICON = 16;
        private final Map<Integer, Icon> icons = new HashMap<>();
        private final Icon hiddenIcon = FontIcon.of(MaterialDesignE.EYE_OFF_OUTLINE, 13, Theme.LIGHT);
        private Row row;
        private boolean selected;
        private boolean focused;

        Cell() {
            setOpaque(false);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            this.row = value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Row r ? r : null;
            this.selected = selected;
            this.focused = tree.hasFocus();
            setFont(tree.getFont());
            setPreferredSize(new Dimension(width(), 22));
            return this;
        }

        private Font bold() {
            return getFont().deriveFont(Font.BOLD);
        }

        private Font idFont() {
            return mono(getFont().getSize2D() - 1f);
        }

        private String name() {
            if (row.isGroup() && row.name().isEmpty()) {
                return "unnamed";
            }
            return row.name();
        }

        private int width() {
            if (row == null) {
                return 10;
            }
            int width = ICON + 6 + getFontMetrics(idFont()).stringWidth(row.idText());
            String name = name();
            if (!name.isEmpty()) {
                width += 8 + getFontMetrics(row.isGroup() ? bold() : getFont()).stringWidth(name);
            }
            if (!row.summary().isEmpty()) {
                width += 10 + getFontMetrics(getFont()).stringWidth(row.summary());
            }
            if (row.selfHidden()) {
                width += 8 + hiddenIcon.getIconWidth();
            }
            return width + 6;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (row == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
                if (hints instanceof Map<?, ?> map) {
                    g.addRenderingHints(map);
                }
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean dim = row.hidden() || drawnKnown && !row.drawn();
                if (dim && !selected) {
                    g.setComposite(AlphaComposite.SrcOver.derive(DIM));
                }
                // The theme picks a dark text for the focused highlight and a light one for the unfocused one
                Color selectedText = UIManager.getColor(focused ? "Tree.selectionForeground"
                        : "Tree.selectionInactiveForeground");
                int height = getHeight();
                Icon icon = icons.computeIfAbsent(row.type(),
                        type -> FontIcon.of(typeIcon(type), ICON, typeColor(type)));
                icon.paintIcon(this, g, 0, (height - icon.getIconHeight()) / 2);
                int x = ICON + 6;

                g.setFont(idFont());
                FontMetrics metrics = g.getFontMetrics();
                int baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
                g.setColor(selected && selectedText != null ? selectedText : row.isGroup() ? Theme.LIGHT : ID_TEXT);
                g.drawString(row.idText(), x, baseline);
                x += metrics.stringWidth(row.idText());

                // A search match shows its name in the accent colour, or its summary if it has no name
                boolean matched = matches.contains(row.key());
                String name = name();
                if (!name.isEmpty()) {
                    x += 8;
                    g.setFont(row.isGroup() ? bold() : getFont());
                    metrics = g.getFontMetrics();
                    baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
                    g.setColor(selected && selectedText != null ? selectedText
                            : matched ? Theme.ACCENT : row.isGroup() && row.name().isEmpty() ? ID_TEXT : Theme.TEXT);
                    g.drawString(name, x, baseline);
                    x += metrics.stringWidth(name);
                }

                if (!row.summary().isEmpty()) {
                    x += 10;
                    g.setFont(getFont());
                    metrics = g.getFontMetrics();
                    baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
                    g.setColor(selected && selectedText != null ? selectedText
                            : matched && name.isEmpty() ? Theme.ACCENT : SUMMARY_TEXT);
                    g.drawString(row.summary(), x, baseline);
                    x += metrics.stringWidth(row.summary());
                }
                if (row.selfHidden()) {
                    hiddenIcon.paintIcon(this, g, x + 8, (height - hiddenIcon.getIconHeight()) / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
