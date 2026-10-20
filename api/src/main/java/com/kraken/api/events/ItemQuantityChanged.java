package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import lombok.Value;

/**
 * An item stack on the ground changed size, as RuneLite's {@code ItemQuantityChanged}. Kraken works this out by
 * comparing piles tick to tick: a stack of the same item on the same tile whose quantity moved.
 */
@Value
public class ItemQuantityChanged {

    /**
     * The stack, whose {@code getQuantity()} already reads the new quantity.
     */
    TileItem item;

    /**
     * The tile the stack is on.
     */
    Tile tile;

    /**
     * The quantity before.
     */
    int oldQuantity;

    /**
     * The quantity now.
     */
    int newQuantity;
}
