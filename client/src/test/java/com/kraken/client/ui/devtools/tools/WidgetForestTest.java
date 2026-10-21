package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Draft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Filtered;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupDraft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import com.kraken.client.ui.devtools.tools.WidgetForest.WidgetNode;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetForest}: where widgets land on the canvas, which widget each group is put under, what a click picks,
 * what a search keeps, and the code that fetches a node.
 */
class WidgetForestTest {

    private static final int TOPLEVEL = 161;
    private static final int INVENTORY = 149;
    private static final Dimension CANVAS = new Dimension(800, 600);

    @Test
    void childrenArePlacedFromTheirParentLessItsScrollAndClippedToIt() {
        Draft items = layer(INVENTORY, 0).at(0, 0, 190, 261).scroll(0, 40)
                .add(dynamic(INVENTORY, 0, 0, WidgetType.GRAPHIC).at(16, 0, 36, 32))
                .add(dynamic(INVENTORY, 0, 3, WidgetType.GRAPHIC).at(16, 50, 36, 32));
        GroupDraft inventory = new GroupDraft(INVENTORY, "INVENTORY")
                .drawn(new Area(500, 200, 190, 261), 500, 200, 0, 0).add(items);

        WidgetForest forest = WidgetForest.assemble(List.of(inventory), CANVAS);

        assertEquals(new Area(516, 210, 36, 32), forest.node("149:0[3]").row().bounds(), "scrolled up by 40");
        Row scrolledAway = forest.node("149:0[0]").row();
        assertEquals(new Area(516, 160, 36, 32), scrolledAway.bounds());
        assertEquals(new Area(516, 200, 36, 0), scrolledAway.visible(), "clipped by the layer");
        assertTrue(forest.pick(520, 170).stream().noneMatch(row -> row.key().equals("149:0[0]")),
                "the part scrolled out of the layer can't be picked");
    }

    @Test
    void aGroupGoesUnderTheEmptyLayerItIsDrawnIn() {
        WidgetForest forest = WidgetForest.assemble(List.of(toplevel(), inventory()), CANVAS);

        assertEquals(1, forest.getRoots().size(), "the inventory is inside the toplevel");
        assertEquals("161:1", forest.groupInfo(INVENTORY).mount(),
                "of two layers at the group's corner, the one with no children");
        assertEquals(List.of("g161", "161:0", "161:1", "g149", "149:0"),
                forest.path("149:0").stream().map(Row::key).toList());
        assertNull(forest.groupInfo(TOPLEVEL).mount());
    }

    @Test
    void aGroupIsNeverPutInsideOneThatIsInsideIt() {
        GroupDraft big = new GroupDraft(TOPLEVEL, "TOPLEVEL").drawn(new Area(0, 0, 800, 600), 0, 0, 0, 0)
                .add(layer(TOPLEVEL, 0).at(0, 0, 800, 600)
                        .add(layer(TOPLEVEL, 1).at(0, 0, 800, 600))
                        .add(graphic(TOPLEVEL, 2).at(10, 10, 20, 20)));
        GroupDraft overlay = new GroupDraft(303, "OVERLAY").drawn(new Area(0, 0, 800, 600), 0, 0, 0, 0)
                .add(layer(303, 0).at(0, 0, 800, 600));
        big.components = 3;
        overlay.components = 1;

        WidgetForest forest = WidgetForest.assemble(List.of(big, overlay), CANVAS);

        assertEquals("161:1", forest.groupInfo(303).mount());
        assertNull(forest.groupInfo(TOPLEVEL).mount(), "the toplevel can't go inside the overlay it holds");
        assertEquals(List.of("g161"), forest.getRoots().stream().map(node -> node.row().key()).toList());
    }

    @Test
    void aHiddenLayerHidesItsChildrenAndTheGroupsInIt() {
        GroupDraft toplevel = toplevel();
        toplevel.roots.get(0).children.get(0).hidden(true);

        WidgetForest forest = WidgetForest.assemble(List.of(toplevel, inventory()), CANVAS);

        Row mount = forest.node("161:1").row();
        assertTrue(mount.selfHidden());
        Row item = forest.node("149:0[0]").row();
        assertTrue(item.hidden(), "hidden through the group it is in");
        assertFalse(item.selfHidden());
    }

    @Test
    void aClickPicksTheTopmostWidgetThatDrawsSomething() {
        GroupDraft group = new GroupDraft(INVENTORY, "INVENTORY").drawn(new Area(0, 0, 800, 600), 0, 0, 0, 0)
                .add(layer(INVENTORY, 0).at(0, 0, 200, 200)
                        .add(graphic(INVENTORY, 1).at(10, 10, 50, 50))
                        .add(graphic(INVENTORY, 2).at(20, 20, 50, 50))
                        .add(layer(INVENTORY, 6).at(20, 20, 0, 0)
                                .add(graphic(INVENTORY, 3).at(0, 0, 50, 50)))
                        .add(graphic(INVENTORY, 4).at(20, 20, 50, 50).hidden(true))
                        .add(layer(INVENTORY, 5).at(0, 0, 200, 200)));

        WidgetForest forest = WidgetForest.assemble(List.of(group), CANVAS);

        assertEquals(List.of("149:2", "149:1", "149:5", "149:0"),
                forest.pick(30, 30).stream().map(Row::key).toList(),
                "drawn last first; layers after the widgets that draw; hidden ones and ones outside their parent "
                        + "left out");
        assertEquals(List.of("149:5", "149:0"), forest.pick(150, 150).stream().map(Row::key).toList());
        assertTrue(forest.pick(300, 300).isEmpty());
    }

    @Test
    void aSearchKeepsMatchesWithTheirAncestorsAndChildren() {
        WidgetForest forest = WidgetForest.assemble(List.of(toplevel(), inventory()), CANVAS);

        Filtered found = forest.filter("ITEMS", false);

        assertEquals(Set.of("149:0"), found.matches());
        WidgetNode root = found.roots().get(0);
        assertEquals("g161", root.row().key());
        WidgetNode items = only(only(only(only(root))));
        assertEquals("149:0", items.row().key());
        assertEquals(2, items.children().size(), "a match keeps all its children");

        assertSame(forest.getRoots(), forest.filter("  ", false).roots(), "a blank search keeps everything");
        assertTrue(forest.filter("no such widget", false).roots().isEmpty());
    }

    @Test
    void theOnScreenFilterDropsHiddenWidgetsAndOnesOutsideTheirParent() {
        GroupDraft inventory = inventory();
        inventory.roots.get(0).children.get(1).hidden(true);
        inventory.roots.get(0).add(dynamic(INVENTORY, 0, 2, WidgetType.GRAPHIC).at(400, 8, 36, 32));

        WidgetForest forest = WidgetForest.assemble(List.of(inventory), CANVAS);

        WidgetNode items = forest.filter("", true).roots().get(0).children().get(0);
        assertEquals(List.of("149:0[0]"), items.children().stream().map(node -> node.row().key()).toList());
        assertFalse(forest.node("149:0[2]").row().onScreen(), "outside the layer it is in");
        assertTrue(forest.node("149:0[0]").row().onScreen());
    }

    @Test
    void codeFetchesANodeByItsNames() {
        WidgetNames names = new WidgetNames(Map.of(INVENTORY, "INVENTORY"),
                Map.of(INVENTORY << 16, new WidgetNames.Name("Inventory", "ITEMS")), Map.of());
        WidgetForest forest = WidgetForest.assemble(List.of(inventory(), new GroupDraft(5000, "")
                .drawn(new Area(0, 0, 10, 10), 0, 0, 0, 0)
                .add(layer(5000, 2).at(0, 0, 10, 10))), CANVAS);

        assertEquals("client.getWidget(InterfaceID.Inventory.ITEMS).getChild(1)",
                WidgetForest.code(forest.path("149:0[1]"), names));
        assertEquals("client.getWidget(InterfaceID.Inventory.ITEMS)", WidgetForest.code(forest.path("149:0"), names));
        assertEquals("client.getWidget(5000, 2)", WidgetForest.code(forest.path("5000:2"), names));
        assertEquals("client.getWidgetGroup(InterfaceID.INVENTORY)", WidgetForest.code(forest.path("g149"), names));
        assertEquals("client.getWidgetGroup(5000)", WidgetForest.code(forest.path("g5000"), names));
    }

    @Test
    void plainTextDropsTheClientsMarkup() {
        assertEquals("Hi there <3", WidgetForest.plain("<col=ff0000>Hi</col><br>there <lt>3"));
        assertEquals("", WidgetForest.plain(null));
        assertEquals("a > b", WidgetForest.plain("a <gt> b"));
    }

    @Test
    void keysTellStaticAndDynamicWidgetsApart() {
        WidgetForest forest = WidgetForest.assemble(List.of(inventory()), CANVAS);

        assertEquals("149:0", forest.node("149:0").row().idText());
        assertEquals("[1]", forest.node("149:0[1]").row().idText());
        assertEquals("149", forest.node("g149").row().idText());
        assertEquals(INVENTORY, forest.node("149:0[1]").row().group());
    }

    /**
     * The toplevel: a full-screen root layer holding two layers at the inventory's corner, the first empty.
     *
     * @return the group
     */
    private static GroupDraft toplevel() {
        GroupDraft group = new GroupDraft(TOPLEVEL, "TOPLEVEL").drawn(new Area(0, 0, 800, 600), 0, 0, 0, 0)
                .add(layer(TOPLEVEL, 0).at(0, 0, 800, 600)
                        .add(layer(TOPLEVEL, 1).at(560, 200, 190, 261))
                        .add(layer(TOPLEVEL, 2).at(560, 200, 190, 261)
                                .add(graphic(TOPLEVEL, 3).at(0, 0, 10, 10))));
        group.components = 4;
        return group;
    }

    /**
     * The inventory, drawn at the toplevel's side panel, with two items.
     *
     * @return the group
     */
    private static GroupDraft inventory() {
        GroupDraft group = new GroupDraft(INVENTORY, "INVENTORY")
                .drawn(new Area(560, 200, 190, 261), 560, 200, 0, 0)
                .add(layer(INVENTORY, 0).at(0, 0, 190, 261).text("ITEMS", "", "")
                        .add(dynamic(INVENTORY, 0, 0, WidgetType.GRAPHIC).at(16, 8, 36, 32))
                        .add(dynamic(INVENTORY, 0, 1, WidgetType.GRAPHIC).at(58, 8, 36, 32)));
        group.components = 1;
        return group;
    }

    private static Draft layer(int group, int component) {
        return new Draft(Kind.STATIC, group << 16 | component, -1, WidgetType.LAYER);
    }

    private static Draft graphic(int group, int component) {
        return new Draft(Kind.STATIC, group << 16 | component, -1, WidgetType.GRAPHIC);
    }

    private static Draft dynamic(int group, int component, int index, int type) {
        return new Draft(Kind.DYNAMIC, group << 16 | component, index, type);
    }

    private static WidgetNode only(WidgetNode node) {
        assertEquals(1, node.children().size(), node.row().key() + " has one child left");
        return node.children().get(0);
    }
}
