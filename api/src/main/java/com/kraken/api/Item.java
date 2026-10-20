package com.kraken.api;

import lombok.Value;

/**
 * One slot of an {@link ItemContainer}, as RuneLite's {@code Item}.
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
