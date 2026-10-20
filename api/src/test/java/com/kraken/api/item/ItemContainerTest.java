package com.kraken.api.item;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.gameval.InventoryID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inventories over the {@link GameFixture}'s inventory table: slots and counts of a snapshot, the equipment
 * slots, inventories the client has no definition for, and the bucket chain the bank sits in.
 */
class ItemContainerTest {

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
    void anItemContainerIsASnapshotOfTheInventory() {
        ItemContainer backpack = client.getItemContainer(InventoryID.INV);
        assertEquals(InventoryID.INV, backpack.getId());
        assertArrayEquals(new Item[]{new Item(1511, 1), new Item(995, 100), new Item(-1, 0), new Item(995, 50)},
                backpack.getItems());
        assertEquals(28, backpack.size(), "from the inventory's definition");
        assertEquals(3, backpack.count());
        assertEquals(150, backpack.count(995));
        assertEquals(0, backpack.count(-1));
        assertEquals(1, backpack.find(995));
        assertEquals(-1, backpack.find(4151));
        assertTrue(backpack.contains(1511));
        assertFalse(backpack.contains(4151));
        assertEquals(new Item(995, 100), backpack.getItem(1));
        assertNull(backpack.getItem(2), "an empty slot");
        assertNull(backpack.getItem(4), "past the slots the client holds");
        assertNull(backpack.getItem(-1), "no slot -1");

        game.setInvSlot(InventoryID.INV, 0, 4151, 1);
        assertEquals(1511, backpack.getItem(0).getId(), "the snapshot doesn't change");
        assertEquals(4151, client.getItemContainer(InventoryID.INV).getItem(0).getId());
    }

    @Test
    void theTotalOfAnItemSumsEverySlot() {
        ItemContainer backpack = client.getItemContainer(InventoryID.INV);
        assertEquals(150, backpack.count(995));
        assertEquals(1, backpack.count(1511));
        assertEquals(0, backpack.count(4151));
        assertEquals(0, backpack.count(-1), "-1 never matches, as in the client");
        assertNull(client.getItemContainer(12_345));
    }

    @Test
    void followsTheBucketChain() {
        game.setInvSlot(InventoryID.BANK, 0, 2434, 7);
        assertEquals(2434, client.getItemContainer(InventoryID.BANK).getItem(0).getId());
        assertEquals(7, client.getItemContainer(InventoryID.BANK).getItem(0).getQuantity());
    }

    @Test
    void getInventoryListsEveryHeldSlot() {
        ItemContainer backpack = client.getItemContainer(InventoryID.INV);
        assertEquals(4, backpack.getItems().length, "every slot the client holds, empty ones included");
        assertEquals(1511, backpack.getItems()[0].getId());
        assertEquals(-1, backpack.getItems()[2].getId(), "an empty slot is an item of id -1");
        assertEquals(0, client.getItemContainer(InventoryID.BANK).count());
        assertNull(client.getItemContainer(12_345));
    }

    @Test
    void getInventoriesWalksEveryBucket() {
        List<Integer> ids = client.getItemContainers().stream().map(ItemContainer::getId).sorted().toList();
        assertEquals(List.of(93, 94, 95), ids);
    }

    @Test
    void wornSlotsAreEquipmentInventorySlots() {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        assertEquals(4151, worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx()).getId());
        assertEquals(14, worn.size());
    }

    @Test
    void anInventoryWithoutADefinitionIsAsBigAsItsSlots() {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        assertEquals(0, bank.size());
        assertEquals(0, bank.getItems().length);
        assertNull(client.getItemContainer(12_345), "an inventory the client doesn't hold");
    }
}
