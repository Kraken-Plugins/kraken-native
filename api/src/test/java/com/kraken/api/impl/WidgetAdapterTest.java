package com.kraken.api.impl;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Point;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetType;
import com.kraken.api.widgets.WidgetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.kraken.api.GameFixture.LONG_TEXT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RuneLite-shaped {@link Widget} over the {@link GameFixture}'s interfaces: ids, the parent and child links,
 * RuneLite's hidden rule and the canvas bounds, each checked against what the Lua mirror reads.
 */
class WidgetAdapterTest {

    private static final int INVENTORY = 149;
    private static final int CHATBOX = 162;

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = Adapters.client(new LuaApi(game.memory, game.map));
    }

    @Test
    void widgetsAreFoundByPackedIdOrGroupAndChild() {
        Widget all = client.getWidget(WidgetUtil.packComponentId(CHATBOX, 1));
        assertEquals(all, client.getWidget(CHATBOX, 1));
        assertEquals(CHATBOX << 16 | 1, all.getId());
        assertEquals(WidgetType.TEXT, all.getType());
        assertEquals(-1, all.getIndex());
        assertEquals("All", all.getText());
        assertEquals(0xffffff, all.getTextColor());
        assertArrayEquals(new String[]{"Switch tab"}, all.getActions());
        assertEquals(5, all.getRelativeX());
        assertEquals(56, all.getWidth());
        assertTrue(all.isRendering());
        assertNull(client.getWidget(151, 0), "a group the client hasn't loaded");
        assertEquals(List.of(INVENTORY, CHATBOX), client.getOpenWidgetGroups());
    }

    @Test
    void widgetUtilPacksAsTheClient() {
        int id = WidgetUtil.packComponentId(CHATBOX, 7);
        assertEquals(CHATBOX, WidgetUtil.componentToInterface(id));
        assertEquals(7, WidgetUtil.componentToId(id));
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
    }

    @Test
    void dynamicChildrenSitAtTheirIndex() {
        Widget root = client.getWidget(INVENTORY, 0);
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
    void hiddenFollowsParentsAndWhetherTheGroupIsOpen() {
        Widget line = client.getWidget(CHATBOX, 2);
        assertTrue(line.isSelfHidden());
        assertTrue(line.isHidden());
        assertFalse(client.getWidget(CHATBOX, 1).isHidden());
        assertTrue(client.getWidget(150, 0).isHidden(), "group 150 is loaded but not open");
        assertFalse(client.getWidget(150, 0).isSelfHidden());

        Widget nested = client.getWidget(INVENTORY, 0).getChild(1).getChild(2);
        assertFalse(nested.isHidden());
        game.memory.putByte(((WidgetImpl) client.getWidget(INVENTORY, 0)).snapshot().getAddress()
                + game.map.offset("IfType.hidden"), 1);
        assertTrue(nested.isHidden(), "a hidden root hides everything under it");
        assertFalse(nested.isSelfHidden());
    }

    @Test
    void boundsAreTheScreenPositionAndSize() {
        Widget all = client.getWidget(CHATBOX, 1);
        assertEquals(new Point(5 + 2, 142 + 461 + 1), all.getCanvasLocation());
        assertEquals(new Rectangle(7, 604, 56, 22), all.getBounds());
        assertTrue(all.contains(new Point(10, 610)));
        assertFalse(all.contains(new Point(6, 610)));
        Widget nested = client.getWidget(INVENTORY, 0).getChild(1).getChild(2);
        assertEquals(new Rectangle(3 + 58 + 970, 4 + 8 + 328, 30, 12), nested.getBounds());
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
        assertEquals(LONG_TEXT, line.getText());
        long width = ((WidgetImpl) line).snapshot().getAddress() + game.map.offset("IfType.width");
        game.memory.putInt(width, 99);
        assertEquals(500, line.getWidth());
        assertEquals(99, client.getWidget(CHATBOX, 2).getWidth());
        assertEquals(line, client.getWidget(CHATBOX, 2), "the same component");
        assertNull(client.getWidget(CHATBOX, 0).getActions(), "no ops at all");
    }
}
