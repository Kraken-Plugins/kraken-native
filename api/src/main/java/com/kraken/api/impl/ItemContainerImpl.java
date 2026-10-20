package com.kraken.api.impl;

import com.kraken.api.Item;
import com.kraken.api.ItemContainer;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;

import java.util.List;

/**
 * An {@link ItemContainer} snapshot of a Lua {@link Inventory}.
 */
final class ItemContainerImpl implements ItemContainer {

    private final int id;
    private final Item[] items;
    private final int size;

    private ItemContainerImpl(int id, Item[] items, int size) {
        this.id = id;
        this.items = items;
        this.size = size;
    }

    /**
     * Copies an inventory's slots.
     *
     * @param inventory   the inventory as the Lua mirror read it
     * @param definedSize the slot count from the inventory's definition ({@code invSize}), or less if unknown
     * @return the snapshot
     */
    static ItemContainerImpl of(Inventory inventory, int definedSize) {
        List<InvItem> slots = inventory.items();
        Item[] items = new Item[slots.size()];
        for (int slot = 0; slot < items.length; slot++) {
            InvItem item = slots.get(slot);
            items[slot] = new Item(item.id(), item.count());
        }
        return new ItemContainerImpl(inventory.id(), items, Math.max(definedSize, items.length));
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public Item[] getItems() {
        return items.clone();
    }

    @Override
    public Item getItem(int slot) {
        if (slot < 0 || slot >= items.length || items[slot].getId() == -1) {
            return null;
        }
        return items[slot];
    }

    @Override
    public boolean contains(int itemId) {
        return find(itemId) != -1;
    }

    @Override
    public int count(int itemId) {
        if (itemId == -1) {
            return 0;
        }
        int total = 0;
        for (Item item : items) {
            if (item.getId() == itemId) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    @Override
    public int count() {
        int occupied = 0;
        for (Item item : items) {
            if (item.getId() != -1) {
                occupied++;
            }
        }
        return occupied;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int find(int itemId) {
        if (itemId == -1) {
            return -1;
        }
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot].getId() == itemId) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "ItemContainer[" + id + ", " + count() + "/" + size + "]";
    }
}
