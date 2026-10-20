package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import lombok.Value;

/**
 * An item stack appeared on the ground, as RuneLite's {@code ItemSpawned}. As in RuneLite, when the client loads a new
 * scene every stack in it is posted as spawned (and none of the old scene's as despawned); so is every stack already
 * there when tracking starts as the API attaches.
 */
@Value
public class ItemSpawned {

    /**
     * The tile the stack is on.
     */
    Tile tile;

    /**
     * The stack: the same handle its quantity change and despawn events carry.
     */
    TileItem item;
}
