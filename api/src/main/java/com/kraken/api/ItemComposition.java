package com.kraken.api;

/**
 * An item's definition, as RuneLite's {@code ItemComposition}: the client's item type, decoded from its config archive
 * as the client loads it (noted and placeholder items merged with the items they stand for, members items rewritten on
 * a free world). Get one from {@link Client#getItemDefinition}.
 */
public interface ItemComposition extends ParamHolder {

    /**
     * The fraction of an item's value high alchemy pays, as in RuneLite.
     */
    float HIGH_ALCHEMY_MULTIPLIER = 0.6f;

    /**
     * The item id.
     *
     * @return the id this definition was looked up with
     */
    int getId();

    /**
     * The item's name.
     *
     * @return the name; "" for an id without a definition; "&lt;name&gt; (Members)" for a members item on a free world
     */
    String getName();

    /**
     * The item's value (its store price).
     *
     * @return the value in coins
     */
    int getPrice();

    /**
     * What high alchemy pays for the item.
     *
     * @return {@link #getPrice()} × {@link #HIGH_ALCHEMY_MULTIPLIER}, rounded down
     */
    default int getHaPrice() {
        return (int) (getPrice() * HIGH_ALCHEMY_MULTIPLIER);
    }

    /**
     * Whether the item stacks in an inventory slot.
     *
     * @return true if it stacks
     */
    boolean isStackable();

    /**
     * Whether the item can be traded.
     *
     * @return true if tradeable
     */
    boolean isTradeable();

    /**
     * Whether the item is members-only.
     *
     * @return true for a members item
     */
    boolean isMembers();

    /**
     * Whether this is a noted item, as RuneLite reports it.
     *
     * @return the note template's id (799) if this is a noted item, else -1
     */
    int getNote();

    /**
     * The noted version of this item, or the unnoted one if this is noted.
     *
     * @return the linked item's id, or -1 if it has none
     */
    int getLinkedNoteId();

    /**
     * This item's bank placeholder, or the item a placeholder stands for.
     *
     * @return the linked item's id, or -1 if none
     */
    int getPlaceholderId();

    /**
     * Whether this is a bank placeholder.
     *
     * @return the placeholder template's id if this is a placeholder, else -1
     */
    int getPlaceholderTemplateId();

    /**
     * The item's inventory actions ("Wield", "Eat", …, usually "Drop" last).
     *
     * @return five entries, index 0 being the first action; null for an empty slot, as in RuneLite
     */
    String[] getInventoryActions();

    /**
     * The item's weight.
     *
     * @return the weight in grams; 0 for items that stack
     */
    int getWeight();

    /**
     * The item's ground actions ("Take", …), with conditional actions resolved against the current vars. Kraken extra,
     * mirrors Lua {@code ObjType.getIOps}.
     *
     * @return the actions, index 0 being the first; null for an empty slot
     */
    String[] getGroundActions();

    /**
     * Whether the item can be worn. Kraken extra.
     *
     * @return true if the item has a worn slot
     */
    boolean isEquipable();

    /**
     * Whether the item is a two-handed weapon. Kraken extra.
     *
     * @return true if it takes the weapon and shield slots
     */
    boolean isTwoHanded();

    /**
     * The worn slot ({@link EquipmentInventorySlot}). Kraken extra.
     *
     * @return the slot index, or -1 if the item can't be worn
     */
    int getSlot();
}
