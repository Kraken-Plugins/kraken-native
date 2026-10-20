package com.kraken.api.item;

import lombok.Value;

/**
 * One slot of an {@link ItemContainer}.
 */
@Value
public class Item {

    /**
     * The item id, or -1 for an empty slot.
     */
    int id;

    /**
     * The stack size, 0 for an empty slot.
     */
    int quantity;
}
