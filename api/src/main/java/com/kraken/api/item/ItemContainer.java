package com.kraken.api.item;

import com.kraken.api.Client;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An inventory (backpack, worn equipment, bank, a shop…), as RuneLite's {@code ItemContainer}. Get one from
 * {@link Client#getItemContainer}.
 *
 * <p>Unlike RuneLite's, this is a snapshot: the slots as they were when the client's inventory table was read. It
 * doesn't change afterwards, so fetch the container again (each tick, say) to see changes. Two snapshots with the same
 * slots are equal.
 */
@EqualsAndHashCode
public final class ItemContainer {

    /**
     * The inventory's id, e.g. {@code InventoryID.INV}.
     */
    @Getter
    private final int id;

    private final Item[] items;
    private final int size;

    /**
     * A snapshot of an inventory's slots.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param id    the inventory id
     * @param items the slots the client holds, empty ones as {@code Item(-1, 0)}; kept, not copied
     * @param size  the slot count from the inventory's definition, or less if unknown
     */
    public ItemContainer(int id, Item[] items, int size) {
        this.id = id;
        this.items = items;
        this.size = Math.max(size, items.length);
    }

    /**
     * Every slot the client holds, empty ones included as {@code Item(-1, 0)}. The client grows an inventory's slots
     * up to the highest one the server has filled, so this can be shorter than {@link #size()}.
     *
     * @return the slots in slot order (a copy)
     */
    public Item[] getItems() {
        return items.clone();
    }

    /**
     * One slot.
     *
     * @param slot the slot, from 0
     * @return the item in it, or null if the slot is empty or out of range
     */
    public Item getItem(int slot) {
        if (slot < 0 || slot >= items.length || items[slot].getId() == -1) {
            return null;
        }
        return items[slot];
    }

    /**
     * Whether any slot holds an item.
     *
     * @param itemId the item id
     * @return true if at least one slot holds it
     */
    public boolean contains(int itemId) {
        return find(itemId) != -1;
    }

    /**
     * How many of an item the inventory holds across all its slots (Lua's {@code invTotal}).
     *
     * @param itemId the item id
     * @return the total stack size; 0 if none
     */
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

    /**
     * How many slots hold something.
     *
     * @return the number of occupied slots
     */
    public int count() {
        int occupied = 0;
        for (Item item : items) {
            if (item.getId() != -1) {
                occupied++;
            }
        }
        return occupied;
    }

    /**
     * How many slots the inventory has, from its definition (Lua's {@code invSize}): 28 for the backpack.
     *
     * @return the slot count; the number of slots the client holds if the definition can't be read
     */
    public int size() {
        return size;
    }

    /**
     * The first slot holding an item.
     *
     * @param itemId the item id
     * @return the slot, or -1 if no slot holds it
     */
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
