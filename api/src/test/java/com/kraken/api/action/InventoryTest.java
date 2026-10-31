package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Inventory}: the reads from the inventory table, and the actions through the slot widgets of the panel shown,
 * the bank's side panel while the bank is open.
 */
class InventoryTest {

    private static final int INVENTORY = InterfaceID.Inventory.ITEMS;
    private static final int COINS = 995;
    private static final int LOGS = 1511;

    private GameFixture game;
    private Client client;
    private Inventory inventory;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        inventory = client.getInventory();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(0), "the batch the matching Actions call sends");
    }

    @Test
    void readsComeFromTheInventoryTable() {
        assertTrue(inventory.contains(COINS));
        assertTrue(inventory.contains(LOGS));
        assertFalse(inventory.contains(4151), "worn, not held");
        assertEquals(150, inventory.count(COINS), "over both slots");
        assertEquals(0, inventory.count(4151));
        assertFalse(inventory.isFull());
        assertFalse(inventory.isEmpty());
        assertEquals(LOGS, inventory.getItems()[0].getId());
        assertEquals(-1, inventory.getItems()[2].getId());

        for (int slot = 0; slot < 28; slot++) {
            game.setInvSlot(93, slot, LOGS, 1);
        }
        assertTrue(inventory.isFull());
    }

    @Test
    void slotsAreFoundByItemAndName() {
        assertEquals(0, inventory.getSlot(COINS).getIndex());
        assertEquals(0, inventory.getSlot("coins").getIndex());
        assertEquals(0, inventory.getSlot("<col=ff9040>Coins</col>").getIndex());
        assertNull(inventory.getSlot(LOGS), "held, but no slot widget shows it");
        assertNull(inventory.getSlot("Logs"));
        assertNull(inventory.getSlot(""));
    }

    @Test
    void anOptionRunsOnTheFirstSlotHoldingTheItem() {
        assertEquals(ActionResult.SENT, inventory.drop(COINS));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 3, COINS);
        assertSentAsReference();
        assertEquals(ActionResult.NOT_FOUND, inventory.interact(LOGS, "Drop"));
        assertEquals(ActionResult.NO_SUCH_OPTION, inventory.interact(COINS, "Eat"));
    }

    @Test
    void anItemIsUsedOnAnNpc() {
        assertEquals(ActionResult.SENT, inventory.use(COINS, client.npc(5)));
        reference.useItemOn(INVENTORY, 0, COINS, 0, 0, MenuAction.WIDGET_TARGET_ON_NPC, 5, -1);
        assertSentAsReference();
        assertEquals(ActionResult.NOT_FOUND, inventory.use(LOGS, client.npc(5)));
    }

    @Test
    void anItemOnItselfNeedsTwoSlots() {
        assertEquals(ActionResult.NOT_FOUND, inventory.use(COINS, COINS));
        assertTrue(sent.isEmpty());
    }

    @Test
    void wieldOrWearTakesTheFirstEquipOpOffered() {
        game.setOps(game.inventoryItem(), "Wear", "Drop");
        assertEquals(ActionResult.NO_SUCH_OPTION, inventory.wield(COINS));
        assertEquals(ActionResult.SENT, inventory.wieldOrWear(COINS));
        reference.menuAction(0, INVENTORY, MenuAction.CC_OP, 1, COINS);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, inventory.wear(COINS));
        game.setOps(game.inventoryItem(), "Drop");
        assertEquals(ActionResult.NO_SUCH_OPTION, inventory.wieldOrWear(COINS));
    }

    @Test
    void theBanksSidePanelWinsWhileItShows() {
        int root = 15 << 16;
        long universe = game.addComponent(root, -1, -1, -1, WidgetType.LAYER, 0, 0, 190, 261);
        long container = game.addComponent(root | 1, -1, -1, root, WidgetType.LAYER, 0, 0, 190, 261);
        long background = game.addComponent(root | 2, -1, -1, root, WidgetType.GRAPHIC, 0, 0, 190, 261);
        long items = game.addComponent(InterfaceID.Bankside.ITEMS, -1, -1, root, WidgetType.LAYER, 0, 0, 190, 261);
        long coins = game.addComponent(InterfaceID.Bankside.ITEMS, 0, -1, InterfaceID.Bankside.ITEMS,
                WidgetType.GRAPHIC, 0, 0, 36, 32);
        game.memory.putInt(coins + game.map.offset("IfType.linkObjType"), COINS);
        game.setOps(coins, "Deposit-1", "Deposit-5", "Deposit-All");
        game.setGroup(15, universe, container, background, items);
        game.setDynamicChildren(items, coins);

        assertEquals(INVENTORY, inventory.getSlot(COINS).getId(), "loaded but not open: the side panel");
        game.openGroup(15, 0, 0);
        assertEquals(InterfaceID.Bankside.ITEMS, inventory.getSlot(COINS).getId());
        assertEquals(ActionResult.SENT, inventory.interact(COINS, "deposit all"));
        reference.menuAction(0, InterfaceID.Bankside.ITEMS, MenuAction.CC_OP, 3, COINS);
        assertSentAsReference();

        game.closeGroup(15);
        assertEquals(INVENTORY, inventory.getSlot(COINS).getId(), "the bank closed");
    }
}
