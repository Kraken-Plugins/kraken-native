package com.kraken.api.widgets;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.CLIENT;
import static com.kraken.api.GameFixture.LONG_TEXT;
import static com.kraken.api.GameFixture.RENDER_CYCLE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interfaces over the {@link GameFixture}: component lookups by packed id or group and child, the parent, static
 * and dynamic child links, the hidden rule and the canvas bounds, the open groups' offsets, and widgets as
 * snapshots of one read.
 */
class WidgetTest {

    private static final int INVENTORY = 149;
    private static final int TOPLEVEL = 161;
    private static final int CHATBOX = 162;

    private GameFixture game;
    private Client client;

    /**
     * Lays out the fixture and a client over it.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    @Test
    void componentsAreFoundByPackedIdOrGroupAndChild() {
        Widget all = client.getWidget(WidgetUtil.packComponentId(CHATBOX, 1));
        assertEquals(all, client.getWidget(CHATBOX, 1));
        assertEquals(CHATBOX << 16 | 1, all.getId());
        assertEquals(WidgetType.TEXT, all.getType());
        assertEquals(-1, all.getIndex(), "a static component's sub id is -1");
        assertTrue(all.isStatic());
        assertEquals("All", all.getText());
        assertEquals(0xffffff, all.getTextColor());
        assertArrayEquals(new String[]{"Switch tab"}, all.getActions());
        assertEquals(5, all.getRelativeX());
        assertEquals(56, all.getWidth());
        assertFalse(all.isSelfHidden());
        assertTrue(all.isRendering());
        assertEquals("Widget[162:1]", all.toString());
        assertNull(client.getWidget(151, 0), "a group the client hasn't loaded");
        assertEquals(List.of(INVENTORY, TOPLEVEL, CHATBOX), client.getOpenWidgetGroups());
    }

    @Test
    void componentsMissingFromTheStoreAreNull() {
        assertNull(client.getWidget(CHATBOX, 3), "past the group's last component");
        assertNull(client.getWidget(GameFixture.IF_GROUP_COUNT, 0), "a group outside the store");
        assertNull(client.getWidget(-1, 0));
        assertNull(client.getWidgetGroup(151));
    }

    @Test
    void widgetUtilPacksAsTheClient() {
        int id = WidgetUtil.packComponentId(CHATBOX, 7);
        assertEquals(CHATBOX, WidgetUtil.componentToInterface(id));
        assertEquals(7, WidgetUtil.componentToId(id));
    }

    @Test
    void readsHeapTextAndFlags() {
        Widget line = client.getWidget(CHATBOX, 2);
        assertEquals(LONG_TEXT, line.getText());
        assertTrue(line.isSelfHidden());
        assertEquals("", line.getText2());
        assertNull(line.getActions(), "no ops");
    }

    @Test
    void aGroupIsItsComponentsWithTheirOffsets() {
        Widget[] chat = client.getWidgetGroup(CHATBOX);
        assertEquals(3, chat.length);
        assertEquals("All", chat[1].getText());
        assertEquals(0, chat[1].getGroupOffset().getX());
        assertEquals(461, chat[1].getGroupOffset().getY());
        assertEquals(2, chat[1].getGroupScriptOffset().getX());

        Widget[] closed = client.getWidgetGroup(150);
        assertEquals(1, closed.length);
        assertNull(closed[0].getGroupOffset(), "not open: no offset");
    }

    @Test
    void openGroupsGiveTheirOffsetsAndClosedOnesNone() {
        assertEquals(970, client.getWidget(INVENTORY, 0).getGroupOffset().getX());
        assertNull(client.getWidget(150, 0).getGroupOffset());
    }

    @Test
    void parentsAndStaticChildrenFollowTheLayerIds() {
        Widget root = client.getWidget(CHATBOX, 0);
        Widget all = client.getWidget(CHATBOX, 1);
        assertEquals(root, all.getParent());
        assertEquals(CHATBOX << 16, all.getParentId());
        assertNull(root.getParent(), "a group's top-level widget");
        assertEquals(-1, root.getParentId());
        assertEquals(List.of(all, client.getWidget(CHATBOX, 2)), Arrays.asList(root.getStaticChildren()));
        assertEquals(0, all.getStaticChildren().length);
        assertEquals(all, all.getStaticParent(), "a static widget is its own static parent");
    }

    @Test
    void dynamicChildrenSitAtTheirIndex() {
        Widget root = client.getWidget(INVENTORY, 0);
        assertEquals(0, client.getWidget(CHATBOX, 0).getDynamicChildren().length, "static children aren't listed");
        Widget[] children = root.getChildren();
        assertEquals(2, children.length, "[2] is under [1], not the root");
        Widget item = children[0];
        assertEquals(0, item.getIndex());
        assertEquals(INVENTORY << 16, item.getId(), "a dynamic widget has its static parent's id");
        assertEquals(995, item.getItemId());
        assertEquals(1000, item.getItemQuantity());
        assertEquals("Coins", item.getName());
        assertArrayEquals(new String[]{"Use", null, "Drop"}, item.getActions());
        assertEquals(root, item.getParent());
        assertEquals(item, root.getChild(0));
        assertEquals(0, item.getChildren().length, "not a layer");

        Widget layer = root.getChild(1);
        Widget[] nested = layer.getChildren();
        assertEquals(3, nested.length);
        assertNull(nested[0]);
        assertEquals("Nested", nested[2].getText());
        assertEquals(List.of(nested[2]), Arrays.asList(layer.getDynamicChildren()));
        assertEquals(layer, nested[2].getParent());
        assertEquals(0, client.getWidget(CHATBOX, 1).getChildren().length, "empty, not null");
    }

    @Test
    void dynamicChildLinksFollowTheLayers() {
        Widget inv = client.getWidget(INVENTORY, 0);
        Widget layer = inv.getChild(1);
        Widget nested = layer.getChild(2);
        assertEquals(layer, nested.getParent());
        assertEquals(inv, nested.getStaticParent());
        assertEquals(inv, nested.getParent().getParent());
        assertEquals(CHATBOX << 16, client.getWidget(CHATBOX, 1).getParentId());
    }

    @Test
    void hiddenFollowsParentsAndWhetherTheGroupIsOpen() {
        Widget line = client.getWidget(CHATBOX, 2);
        assertTrue(line.isSelfHidden());
        assertTrue(line.isHidden());
        assertFalse(client.getWidget(CHATBOX, 1).isHidden());
        assertTrue(client.getWidget(150, 0).isHidden(), "group 150 is loaded but not open");
        assertFalse(client.getWidget(150, 0).isSelfHidden());

        Widget nested = client.getWidget(INVENTORY, 0).getChild(1).getChild(2);
        assertFalse(nested.isHidden());
        game.memory.putByte(client.getWidget(INVENTORY, 0).getAddress() + game.map.offset("IfType.hidden"), 1);
        assertTrue(nested.isHidden(), "a hidden root hides everything under it");
        assertFalse(nested.isSelfHidden());
    }

    @Test
    void aGroupsTopLevelWidgetsParentIsTheComponentItIsOpenedIn() {
        int panel = TOPLEVEL << 16 | 1;
        assertEquals(Map.of(panel, new WidgetNode(INVENTORY)), client.getComponentTable());

        Widget root = client.getWidget(INVENTORY, 0);
        assertEquals(panel, root.getParentId());
        assertEquals(client.getWidget(panel), root.getParent());
        assertEquals(client.getWidget(TOPLEVEL, 0), root.getParent().getParent());
        assertNull(client.getWidget(TOPLEVEL, 0).getParent(), "the toplevel is opened in nothing");
        assertEquals(-1, client.getWidget(CHATBOX, 0).getParentId(), "the chatbox isn't in the table");
    }

    @Test
    void aHiddenComponentHidesTheInterfaceOpenedInIt() {
        Widget item = client.getWidget(INVENTORY, 0).getChild(0);
        assertFalse(item.isHidden());

        game.memory.putByte(client.getWidget(TOPLEVEL, 1).getAddress() + game.map.offset("IfType.hidden"), 1);

        assertTrue(item.isHidden(), "the side panel the inventory is opened in is hidden, as an inactive tab's is");
        assertFalse(item.isSelfHidden());
        assertEquals(new Point(16 + 970, 8 + 328), item.getCanvasLocation(),
                "the position comes from where the inventory is drawn, not from the panel");
    }

    @Test
    void boundsAreTheScreenPositionAndSize() {
        Widget all = client.getWidget(CHATBOX, 1);
        assertEquals(new Point(5 + 2, 142 + 461 + 1), all.getCanvasLocation());
        assertEquals(new Rectangle(7, 604, 56, 22), all.getBounds());
        assertTrue(all.contains(new Point(10, 610)));
        assertFalse(all.contains(new Point(6, 610)));
        Widget nested = client.getWidget(INVENTORY, 0).getChild(1).getChild(2);
        assertEquals(new Point(3 + 58 + 970, 4 + 8 + 328), nested.getCanvasLocation());
        assertEquals(new Rectangle(3 + 58 + 970, 4 + 8 + 328, 30, 12), nested.getBounds());
    }

    @Test
    void notOpenGroupsHaveNoScreenOffset() {
        assertEquals(new Point(0, 0), client.getWidget(150, 0).getCanvasLocation(), "not open: no offset");
    }

    @Test
    void renderingComparesTheDrawStampWithTheFrame() {
        assertTrue(client.getWidget(CHATBOX, 1).isRendering());
        assertFalse(client.getWidget(CHATBOX, 2).isRendering());
        game.memory.putInt(CLIENT + game.map.offset("Client.renderCycle"), RENDER_CYCLE + 1);
        assertFalse(client.getWidget(CHATBOX, 1).isRendering());
    }

    @Test
    void theGroupClipComesFromTheOpenGroup() {
        game.setGroupClip(CHATBOX, 0, 461, 519, 626);
        assertEquals(new Rectangle(0, 461, 519, 165), client.getWidget(CHATBOX, 1).getGroupClipBounds());
        assertNull(client.getWidget(150, 0).getGroupClipBounds(), "not open");
    }

    @Test
    void aWidgetIsASnapshot() {
        Widget line = client.getWidget(CHATBOX, 2);
        assertEquals(500, line.getWidth());
        game.memory.putInt(line.getAddress() + game.map.offset("IfType.width"), 99);
        assertEquals(500, line.getWidth(), "the snapshot keeps the value it read");
        assertEquals(99, client.getWidget(CHATBOX, 2).getWidth());
        assertEquals(line, client.getWidget(CHATBOX, 2), "the same component");
    }

    @Test
    void childLookupFindsOneDirectChildBySubId() {
        Widget root = client.getWidget(INVENTORY, 0);
        assertEquals("Widget[149:0[0]]", root.getChild(0).toString());
        assertEquals("Widget[149:0[1]]", root.getChild(1).toString());
        assertNull(root.getChild(2), "[2] sits inside [1], not directly under the root");
        assertNull(root.getChild(3), "past the last child");
        assertNull(root.getChild(-1));
        Widget layer = root.getChild(1);
        assertEquals("Nested", layer.getChild(2).getText());
        assertNull(layer.getChild(0), "outside the layer's range");
        assertNull(client.getWidget(CHATBOX, 1).getChild(0), "only a layer has children");
    }
}
