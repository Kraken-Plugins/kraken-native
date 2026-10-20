package com.kraken.api;

/**
 * An inventory (backpack, worn equipment, bank, a shop…), as RuneLite's {@code ItemContainer}.
 *
 * <p>Unlike RuneLite's, this is a snapshot: the slots as they were when {@link Client#getItemContainer} read them. It
 * doesn't change afterwards, so fetch the container again (each tick, say) to see changes.
 */
public interface ItemContainer {

    /**
     * The inventory's id.
     *
     * @return the id, e.g. {@code InventoryID.INV}
     */
    int getId();

    /**
     * Every slot the client holds, empty ones included as {@code Item(-1, 0)}. The client grows an inventory's slots
     * up to the highest one the server has filled, so this can be shorter than {@link #size()}.
     *
     * @return the slots in slot order (a copy)
     */
    Item[] getItems();

    /**
     * One slot.
     *
     * @param slot the slot, from 0
     * @return the item in it, or null if the slot is empty or out of range
     */
    Item getItem(int slot);

    /**
     * Whether any slot holds an item.
     *
     * @param itemId the item id
     * @return true if at least one slot holds it
     */
    boolean contains(int itemId);

    /**
     * How many of an item the inventory holds across all its slots (Lua's {@code invTotal}).
     *
     * @param itemId the item id
     * @return the total stack size; 0 if none
     */
    int count(int itemId);

    /**
     * How many slots hold something.
     *
     * @return the number of occupied slots
     */
    int count();

    /**
     * How many slots the inventory has, from its definition (Lua's {@code invSize}): 28 for the backpack.
     *
     * @return the slot count; the number of slots the client holds if the definition can't be read
     */
    int size();

    /**
     * The first slot holding an item.
     *
     * @param itemId the item id
     * @return the slot, or -1 if no slot holds it
     */
    int find(int itemId);
}
