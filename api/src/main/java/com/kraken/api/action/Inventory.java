package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.item.Item;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;

/**
 * The inventory. Get it from {@code client.getInventory()} or inject it.
 *
 * <p>The reads come from the inventory table ({@code InventoryID.INV}), which follows the server. The actions go
 * through the slot widgets of the inventory panel the client shows now: the bank's side panel while the bank is open,
 * a shop's while a shop is open, the deposit box's, else the side panel's. So
 * {@code inventory.interact(ItemID.COINS, "Deposit-All")} works while the bank is open.
 */
public final class Inventory {

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Inventory(Client client) {
        this.client = client;
    }

    /**
     * Whether the inventory holds an item.
     *
     * @param itemId the item's id
     * @return true if a slot holds it
     */
    public boolean contains(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * How many of an item the inventory holds, over all its slots.
     *
     * @param itemId the item's id
     * @return the total quantity; 0 if none
     */
    public int count(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether every slot of the inventory holds an item.
     *
     * @return true if it is full
     */
    public boolean isFull() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether no slot of the inventory holds an item.
     *
     * @return true if it is empty
     */
    public boolean isEmpty() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The inventory's items, by slot.
     *
     * @return the items, empty slots as id -1; empty if the inventory can't be read
     */
    public Item[] getItems() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The first slot widget holding an item, in the inventory panel shown now.
     *
     * @param itemId the item's id
     * @return the slot's widget, or null if no slot holds it or no inventory panel is loaded
     */
    public Widget getSlot(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The first slot widget holding an item with a name, in the inventory panel shown now. The name is matched
     * ignoring markup and case.
     *
     * @param name the item's name
     * @return the slot's widget, or null if no slot holds such an item or no inventory panel is loaded
     */
    public Widget getSlot(String name) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Runs one of an item's options, on the first slot holding it.
     *
     * @param itemId the item's id
     * @param option the option's text, matched as {@link Widget#interact(String)} matches
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no slot holds it
     */
    public ActionResult interact(int itemId, String option) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Uses one item on another, both in the inventory.
     *
     * @param itemId   the item to use
     * @param onItemId the item to use it on
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if either isn't held
     */
    public ActionResult use(int itemId, int onItemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Uses an item on an NPC.
     *
     * @param itemId the item to use
     * @param target the NPC
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, NPC target) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Uses an item on a player.
     *
     * @param itemId the item to use
     * @param target the player
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, Player target) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Uses an item on an object.
     *
     * @param itemId the item to use
     * @param target the object
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, TileObject target) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Uses an item on an item on the ground.
     *
     * @param itemId the item to use
     * @param target the ground item
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, TileItem target) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Drops an item: {@code interact(itemId, "Drop")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent
     */
    public ActionResult drop(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Wields a weapon or shield: {@code interact(itemId, "Wield")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the item has
     *         no Wield option
     */
    public ActionResult wield(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Wears armour or jewellery: {@code interact(itemId, "Wear")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the item has
     *         no Wear option
     */
    public ActionResult wear(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Equips an item by whichever of Wield, Wear and Equip it offers, in that order.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if it offers none
     *         of them
     */
    public ActionResult wieldOrWear(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }
}
