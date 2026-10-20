package com.kraken.api.events;

import com.kraken.api.item.ItemContainer;
import lombok.Value;

/**
 * An inventory's contents changed, or the client started holding it, as RuneLite's {@code ItemContainerChanged}.
 * Unlike Lua's {@code ON_INV_TRANSMIT}, which lists the changed slots, it carries the whole container. An inventory
 * the client drops (it clears them all at login) is forgotten without an event.
 */
@Value
public class ItemContainerChanged {

    /**
     * The inventory id, e.g. {@code InventoryID.INV}.
     */
    int containerId;

    /**
     * The inventory as it is now: a snapshot, as {@code Client.getItemContainer} returns.
     */
    ItemContainer itemContainer;
}
