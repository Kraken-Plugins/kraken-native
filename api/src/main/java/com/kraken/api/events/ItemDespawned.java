package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import lombok.Value;

/**
 * An item stack went from the ground (picked up, or despawned), as RuneLite's {@code ItemDespawned}. Not posted for
 * the stacks of a scene the client unloads.
 */
@Value
public class ItemDespawned {

    /**
     * The tile the stack was on.
     */
    Tile tile;

    /**
     * The stack: the handle its spawn event carried.
     */
    TileItem item;
}
