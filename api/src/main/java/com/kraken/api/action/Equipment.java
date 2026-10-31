package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.api.item.Item;

/**
 * The worn items. Get it from {@code client.getEquipment()} or inject it.
 *
 * <p>The reads come from the worn-items table ({@code InventoryID.WORN}). The actions run the ops of the worn-items
 * interface's slot components ({@link EquipmentInventorySlot#getComponentId()}).
 */
public final class Equipment {

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Equipment(Client client) {
        this.client = client;
    }

    /**
     * Whether an item is worn.
     *
     * @param itemId the item's id
     * @return true if a worn slot holds it
     */
    public boolean isEquipped(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The item worn in a slot.
     *
     * @param slot the slot
     * @return the item, or null if the slot is empty or the worn items can't be read
     */
    public Item getItem(EquipmentInventorySlot slot) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Takes off the item worn in a slot: the slot's "Remove".
     *
     * @param slot the slot
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded
     */
    public ActionResult remove(EquipmentInventorySlot slot) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Runs one of the ops of the item worn in a slot, such as "Rub".
     *
     * @param slot   the slot
     * @param option the op's text, matched loosely
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded
     */
    public ActionResult interact(EquipmentInventorySlot slot, String option) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Runs one of the ops of the item worn in a slot with one of its sub-options, such as a teleport's destination.
     *
     * @param slot      the slot
     * @param option    the op's text, matched loosely
     * @param subOption the sub-option's text, matched loosely
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded
     */
    public ActionResult interact(EquipmentInventorySlot slot, String option, String subOption) {
        throw new UnsupportedOperationException("not built yet");
    }
}
