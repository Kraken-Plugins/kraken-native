package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.api.item.Item;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.widgets.Widget;

import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The worn items. Get it from {@code client.getEquipment()} or inject it.
 *
 * <p>The reads come from the worn-items table ({@code InventoryID.WORN}). The actions run the ops of the worn-items
 * interface's slot components ({@link EquipmentInventorySlot#getComponentId()}), which carry the worn item's id, so
 * a component op names the item the slot holds.
 */
public final class Equipment {

    private static final Logger log = Logger.getLogger("kraken.api");

    private static final String REMOVE = "Remove";

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
        ItemContainer worn = worn();
        return worn != null && worn.contains(itemId);
    }

    /**
     * The item worn in a slot.
     *
     * @param slot the slot
     * @return the item, or null if the slot is empty or the worn items can't be read
     */
    public Item getItem(EquipmentInventorySlot slot) {
        ItemContainer worn = worn();
        return worn == null ? null : worn.getItem(slot.getSlotIdx());
    }

    /**
     * Takes off the item worn in a slot: the slot's "Remove".
     *
     * @param slot the slot
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded, {@link ActionResult#NO_SUCH_OPTION} if the worn item has no
     *         Remove
     */
    public ActionResult remove(EquipmentInventorySlot slot) {
        return withSlot(slot, component -> component.interact(REMOVE));
    }

    /**
     * Runs one of the ops of the item worn in a slot, such as "Rub".
     *
     * @param slot   the slot
     * @param option the op's text, matched loosely
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded, {@link ActionResult#NO_SUCH_OPTION} if the item has no such op
     */
    public ActionResult interact(EquipmentInventorySlot slot, String option) {
        Objects.requireNonNull(option, "option");
        return withSlot(slot, component -> component.interact(option));
    }

    /**
     * Runs one of the ops of the item worn in a slot with one of its sub-options, such as a teleport's destination.
     *
     * @param slot      the slot
     * @param option    the op's text, matched loosely
     * @param subOption the sub-option's text, matched loosely
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the slot is empty
     *         or the worn-items interface isn't loaded, {@link ActionResult#NO_SUCH_OPTION} if the op or its sub-option
     *         isn't there
     */
    public ActionResult interact(EquipmentInventorySlot slot, String option, String subOption) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(subOption, "subOption");
        return withSlot(slot, component -> component.interact(option, subOption));
    }

    private ItemContainer worn() {
        return client.getItemContainer(InventoryID.WORN);
    }

    /**
     * Runs an action on a slot's component, if the slot holds an item and the worn-items interface is loaded.
     *
     * @param slot   the slot
     * @param action the action to run on the component
     * @return the action's result, or {@link ActionResult#NOT_FOUND} if the slot is empty or its component isn't loaded
     */
    private ActionResult withSlot(EquipmentInventorySlot slot, Function<Widget, ActionResult> action) {
        if (getItem(slot) == null) {
            log.fine(() -> slot + " holds no worn item");
            return ActionResult.NOT_FOUND;
        }
        Widget component = client.getWidget(slot.getComponentId());
        if (component == null) {
            log.fine(() -> "the worn-items interface isn't loaded; " + slot + " can't be used");
            return ActionResult.NOT_FOUND;
        }
        return action.apply(component);
    }
}
