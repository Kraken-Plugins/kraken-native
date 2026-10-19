package com.kraken.api.game;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The ClientOp inventory functions over the {@link GameFixture}'s inventory table, including the chained bucket. */
class InventoryReadTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    @Test
    void invGetObjIdReadsSlotsAndFallsBackToMinusOne() {
        assertEquals(1511, client.invGetObjId(Client.INV_BACKPACK, 0));
        assertEquals(995, client.invGetObjId(Client.INV_BACKPACK, 1));
        assertEquals(-1, client.invGetObjId(Client.INV_BACKPACK, 2), "empty slot");
        assertEquals(-1, client.invGetObjId(Client.INV_BACKPACK, 4), "past the slots the client holds");
        assertEquals(-1, client.invGetObjId(Client.INV_BACKPACK, -1));
        assertEquals(-1, client.invGetObjId(12345, 0), "inventory not loaded");
        assertEquals(4151, client.invGetObjId(Client.INV_WORN, Client.WornSlots.WEAPON.slot()));
    }

    @Test
    void invGetNumIsZeroWhenMissingLikeTheNativeHandler() {
        assertEquals(100, client.invGetNum(Client.INV_BACKPACK, 1));
        assertEquals(0, client.invGetNum(Client.INV_BACKPACK, 2));
        assertEquals(0, client.invGetNum(Client.INV_BACKPACK, 30));
        assertEquals(0, client.invGetNum(12345, 0));
    }

    @Test
    void invTotalSumsEverySlotOfTheItem() {
        assertEquals(150, client.invTotal(Client.INV_BACKPACK, 995));
        assertEquals(1, client.invTotal(Client.INV_BACKPACK, 1511));
        assertEquals(0, client.invTotal(Client.INV_BACKPACK, 4151));
        assertEquals(0, client.invTotal(Client.INV_BACKPACK, -1), "-1 never matches, as in the client");
        assertEquals(0, client.invTotal(12345, 995));
    }

    @Test
    void followsTheBucketChain() {
        game.setInvSlot(Client.INV_BANK, 0, 2434, 7);
        assertEquals(2434, client.invGetObjId(Client.INV_BANK, 0));
        assertEquals(7, client.invGetNum(Client.INV_BANK, 0));
    }

    @Test
    void getInventoryListsEveryHeldSlot() {
        Inventory backpack = client.getInventory(Client.INV_BACKPACK);
        assertNotNull(backpack);
        assertEquals(List.of(new InvItem(1511, 0, 1), new InvItem(995, 1, 100), new InvItem(-1, 2, 0),
                new InvItem(995, 3, 50)), backpack.items());
        assertEquals(List.of(), client.getInventory(Client.INV_BANK).items());
        assertNull(client.getInventory(12345));
    }

    @Test
    void getInventoriesWalksEveryBucket() {
        List<Integer> ids = client.getInventories().stream().map(Inventory::id).sorted().toList();
        assertEquals(List.of(93, 94, 95), ids);
    }
}
