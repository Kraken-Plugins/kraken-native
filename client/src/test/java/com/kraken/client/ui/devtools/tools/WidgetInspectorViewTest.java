package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Draft;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupDraft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetInspectorView}'s tree as the game changes: new forests merge into the rows shown, keeping what is open
 * and selected, and selecting a node the search hides clears the search.
 */
class WidgetInspectorViewTest {

    private static final Dimension CANVAS = new Dimension(800, 600);

    @Test
    void newForestsKeepOpenRowsAndTheSelection() throws Exception {
        WidgetInspectorTool tool = new WidgetInspectorTool();
        JTree[] tree = new JTree[1];
        SwingUtilities.invokeAndWait(() -> {
            WidgetInspectorView view = new WidgetInspectorView(tool);
            tree[0] = find(view.component(), JTree.class).get(0);
            view.update(snapshot(inventory(List.of(0, 1, 2), "Shark")));
            view.selectKey("149:0[1]");
            assertTrue(tree[0].isExpanded(path(tree[0], "149:0")), "opened to show the selection");

            view.update(snapshot(inventory(List.of(1, 2, 5), "Lobster")));
        });

        assertEquals("149:0[1]", selectedKey(tree[0]), "the selection stays on the same widget");
        assertEquals("149:0[1]", tool.selected(), "merging rows doesn't change what the tool reads");
        assertEquals("Lobster", row(tree[0], "149:0[1]").summary(), "a changed row shows its new values");
        assertTrue(tree[0].isExpanded(path(tree[0], "149:0")), "the open layer stays open");
        assertEquals(List.of("149:0[1]", "149:0[2]", "149:0[5]"), childKeys(tree[0], "149:0"),
                "gone rows removed, new ones inserted in order");
    }

    @Test
    void rootsThatChangeOrderStayOpen() throws Exception {
        WidgetInspectorTool tool = new WidgetInspectorTool();
        JTree[] tree = new JTree[1];
        SwingUtilities.invokeAndWait(() -> {
            WidgetInspectorView view = new WidgetInspectorView(tool);
            tree[0] = find(view.component(), JTree.class).get(0);
            view.update(snapshot(WidgetForest.assemble(List.of(group(149, 3), group(300, 1)), CANVAS)));
            tree[0].expandPath(path(tree[0], "149:0"));
            view.update(snapshot(WidgetForest.assemble(List.of(group(149, 1), group(300, 4)), CANVAS)));
        });

        assertEquals("g300", row(tree[0].getPathForRow(0)).key(), "the bigger group comes first");
        assertTrue(tree[0].isExpanded(path(tree[0], "149:0")), "rows under a moved root stay open");
    }

    @Test
    void selectingANodeTheSearchHidesClearsTheSearch() throws Exception {
        WidgetInspectorTool tool = new WidgetInspectorTool();
        JTree[] tree = new JTree[1];
        JTextField[] search = new JTextField[1];
        SwingUtilities.invokeAndWait(() -> {
            WidgetInspectorView view = new WidgetInspectorView(tool);
            tree[0] = find(view.component(), JTree.class).get(0);
            search[0] = find(view.component(), JTextField.class).get(0);
            view.update(snapshot(inventory(List.of(0, 1), "Shark")));
            search[0].setText("[0]");
            assertNull(path(tree[0], "149:0[1]"), "the search hides the other item");

            view.selectKey("149:0[1]");
        });

        assertEquals("", search[0].getText());
        assertEquals("149:0[1]", selectedKey(tree[0]));
    }

    private static WidgetInspectorTool.Snapshot snapshot(WidgetForest forest) {
        return new WidgetInspectorTool.Snapshot(forest, null, Marks.NONE, false);
    }

    /**
     * The inventory with items at some slots.
     *
     * @param slots   the filled slots
     * @param summary what slot 1 shows
     * @return the forest
     */
    private static WidgetForest inventory(List<Integer> slots, String summary) {
        Draft items = new Draft(Kind.STATIC, 149 << 16, -1, WidgetType.LAYER).at(0, 0, 190, 261)
                .text("ITEMS", "", "");
        for (int slot : slots) {
            items.add(new Draft(Kind.DYNAMIC, 149 << 16, slot, WidgetType.GRAPHIC).at(slot * 40, 0, 36, 32)
                    .text("", slot == 1 ? summary : "Coins", ""));
        }
        GroupDraft group = new GroupDraft(149, "INVENTORY").drawn(new Area(0, 0, 190, 261), 0, 0, 0, 0).add(items);
        return WidgetForest.assemble(List.of(group), CANVAS);
    }

    /**
     * A group with a root layer and some graphics under it, drawn where no other group is.
     *
     * @param id      the group id
     * @param widgets how many graphics
     * @return the group
     */
    private static GroupDraft group(int id, int widgets) {
        Draft root = new Draft(Kind.STATIC, id << 16, -1, WidgetType.LAYER).at(0, 0, 50, 50);
        for (int i = 1; i <= widgets; i++) {
            root.add(new Draft(Kind.STATIC, id << 16 | i, -1, WidgetType.GRAPHIC).at(0, 0, 5, 5));
        }
        return new GroupDraft(id, "").drawn(new Area(id, id, 50, 50), id, id, 0, 0).add(root);
    }

    private static TreePath path(JTree tree, String key) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var all = root.preorderEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) all.nextElement();
            if (node.getUserObject() instanceof Row row && row.key().equals(key)) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    private static Row row(JTree tree, String key) {
        return row(path(tree, key));
    }

    private static Row row(TreePath path) {
        return (Row) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private static String selectedKey(JTree tree) {
        TreePath selected = tree.getSelectionPath();
        return selected == null ? null : row(selected).key();
    }

    private static List<String> childKeys(JTree tree, String key) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path(tree, key).getLastPathComponent();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            keys.add(((Row) ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject()).key());
        }
        return keys;
    }

    private static <T extends Component> List<T> find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(find(container, type));
            }
        }
        return found;
    }
}
