package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.internal.action.Interactions;
import com.kraken.api.internal.action.OptionMatcher;
import com.kraken.api.item.Item;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.widgets.Widget;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The inventory. Get it from {@code client.getInventory()} or inject it.
 *
 * <p>The reads come from the inventory table ({@code InventoryID.INV}), which follows the server. The actions go
 * through the slot widgets of the inventory panel the client shows now: the bank's side panel while the bank is open,
 * a shop's while a shop is open, the deposit box's, else the side panel's. So
 * {@code inventory.interact(ItemID.COINS, "Deposit-All")} works while the bank is open.
 */
public final class Inventory {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The inventory panels the actions go through, the first one showing winning: the bank's side panel, a shop's,
     * the deposit box's and the side panel's.
     */
    private static final int[] PANELS = {InterfaceID.Bankside.ITEMS, InterfaceID.Shopside.ITEMS,
            InterfaceID.BankDepositbox.INVENTORY, InterfaceID.Inventory.ITEMS};

    /**
     * The ops that put an item on, in the order {@link #wieldOrWear} tries them.
     */
    private static final String[] EQUIP_OPS = {"Wield", "Wear", "Equip"};

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
        ItemContainer items = container();
        return items != null && items.contains(itemId);
    }

    /**
     * How many of an item the inventory holds, over all its slots.
     *
     * @param itemId the item's id
     * @return the total quantity; 0 if none
     */
    public int count(int itemId) {
        ItemContainer items = container();
        return items == null ? 0 : items.count(itemId);
    }

    /**
     * Whether every slot of the inventory holds an item.
     *
     * @return true if it is full; false if it can't be read
     */
    public boolean isFull() {
        ItemContainer items = container();
        return items != null && items.count() >= items.size();
    }

    /**
     * Whether no slot of the inventory holds an item.
     *
     * @return true if it is empty; false if it can't be read
     */
    public boolean isEmpty() {
        ItemContainer items = container();
        return items != null && items.count() == 0;
    }

    /**
     * The inventory's items, by slot.
     *
     * @return the items, empty slots as id -1; empty if the inventory can't be read
     */
    public Item[] getItems() {
        ItemContainer items = container();
        return items == null ? new Item[0] : items.getItems();
    }

    /**
     * The first slot widget holding an item, in the inventory panel shown now.
     *
     * @param itemId the item's id
     * @return the slot's widget, or null if no slot holds it or no inventory panel is loaded
     */
    public Widget getSlot(int itemId) {
        return slot(slot -> slot.getItemId() == itemId);
    }

    /**
     * The first slot widget holding an item with a name, in the inventory panel shown now. The name is matched
     * ignoring markup and case.
     *
     * @param name the item's name
     * @return the slot's widget, or null if no slot holds such an item or no inventory panel is loaded
     */
    public Widget getSlot(String name) {
        String wanted = OptionMatcher.plain(Objects.requireNonNull(name, "name"));
        if (wanted.isEmpty()) {
            return null;
        }
        return slot(slot -> {
            ItemComposition type = client.getItemDefinition(slot.getItemId());
            return type != null && OptionMatcher.plain(type.getName()).equalsIgnoreCase(wanted);
        });
    }

    /**
     * Runs one of an item's options, on the first slot holding it.
     *
     * @param itemId the item's id
     * @param option the option's text, matched as {@link Widget#interact(String)} matches
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no slot holds it
     */
    public ActionResult interact(int itemId, String option) {
        Objects.requireNonNull(option, "option");
        return withSlot(itemId, slot -> slot.interact(option));
    }

    /**
     * Uses one item on another, both in the inventory. Using an item on itself takes two slots holding it.
     *
     * @param itemId   the item to use
     * @param onItemId the item to use it on
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if either isn't held
     */
    public ActionResult use(int itemId, int onItemId) {
        return withSlot(itemId, source -> {
            Widget target = slot(slot -> slot.getItemId() == onItemId && slot.getIndex() != source.getIndex());
            return target == null ? notFound(onItemId) : source.useOn(target);
        });
    }

    /**
     * Uses an item on an NPC.
     *
     * @param itemId the item to use
     * @param target the NPC
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, NPC target) {
        return withSlot(itemId, slot -> slot.useOn(target));
    }

    /**
     * Uses an item on a player.
     *
     * @param itemId the item to use
     * @param target the player
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, Player target) {
        return withSlot(itemId, slot -> slot.useOn(target));
    }

    /**
     * Uses an item on an object.
     *
     * @param itemId the item to use
     * @param target the object
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, TileObject target) {
        return withSlot(itemId, slot -> slot.useOn(target));
    }

    /**
     * Uses an item on an item on the ground.
     *
     * @param itemId the item to use
     * @param target the ground item
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the item isn't held
     */
    public ActionResult use(int itemId, TileItem target) {
        return withSlot(itemId, slot -> slot.useOn(target));
    }

    /**
     * Drops an item: {@code interact(itemId, "Drop")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent
     */
    public ActionResult drop(int itemId) {
        return interact(itemId, "Drop");
    }

    /**
     * Wields a weapon or shield: {@code interact(itemId, "Wield")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the item has
     *         no Wield option
     */
    public ActionResult wield(int itemId) {
        return interact(itemId, "Wield");
    }

    /**
     * Wears armour or jewellery: {@code interact(itemId, "Wear")}.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if the item has
     *         no Wear option
     */
    public ActionResult wear(int itemId) {
        return interact(itemId, "Wear");
    }

    /**
     * Equips an item by whichever of Wield, Wear and Equip it offers, in that order.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} if it offers none
     *         of them
     */
    public ActionResult wieldOrWear(int itemId) {
        return withSlot(itemId, slot -> {
            String[] options = client.getInteractions().actions(slot);
            for (String op : EQUIP_OPS) {
                if (OptionMatcher.match(options, op, Interactions.COMPONENT_OPS) > 0) {
                    return slot.interact(op);
                }
            }
            log.fine(() -> "item " + itemId + " has no Wield, Wear or Equip option; it has "
                    + OptionMatcher.describe(options));
            return ActionResult.NO_SUCH_OPTION;
        });
    }

    private ItemContainer container() {
        return client.getItemContainer(InventoryID.INV);
    }

    private ActionResult withSlot(int itemId, Function<Widget, ActionResult> action) {
        Widget slot = getSlot(itemId);
        return slot == null ? notFound(itemId) : action.apply(slot);
    }

    private static ActionResult notFound(int itemId) {
        log.fine(() -> "no inventory slot shown holds item " + itemId);
        return ActionResult.NOT_FOUND;
    }

    /**
     * The first slot of the panel shown that passes a test.
     *
     * @param wanted the test
     * @return the slot's widget, or null if none passes or no panel is loaded
     */
    private Widget slot(Predicate<Widget> wanted) {
        Widget panel = panel();
        if (panel == null) {
            return null;
        }
        for (Widget slot : panel.getDynamicChildren()) {
            if (slot.getItemId() >= 0 && wanted.test(slot)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * The inventory panel the actions go through: the first of {@link #PANELS} that is showing, else the side panel's
     * while it is loaded behind another tab.
     *
     * @return the panel's widget, or null if none is loaded
     */
    private Widget panel() {
        for (int id : PANELS) {
            Widget panel = client.getWidget(id);
            if (panel != null && !panel.isHidden()) {
                return panel;
            }
        }
        return client.getWidget(InterfaceID.Inventory.ITEMS);
    }
}
