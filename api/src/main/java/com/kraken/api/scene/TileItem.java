package com.kraken.api.scene;

import lombok.Getter;
import lombok.Setter;

/**
 * An item stack lying on the ground. The whole pile on a tile is read at once ({@link Tile#getGroundItems()}). The
 * despawn time, ownership and privacy aren't read.
 *
 * <p>A handle with identity: the item spawn events carry one handle per stack, the same in its spawn, quantity change
 * and despawn events, and its quantity follows {@code ItemQuantityChanged}. {@link Tile#getGroundItems()} and
 * {@link Scene#getGroundItems()} make new handles each call, so compare those by id and tile.
 */
@Getter
public final class TileItem {

    /**
     * The item's id, as in {@code gameval.ItemID}.
     */
    private final int id;

    /**
     * The stack's size, as last read. Internal: the event source sets it as it follows the stack.
     */
    @Setter
    private volatile int quantity;

    /**
     * The tile the stack lies on.
     */
    private final Tile tile;

    /**
     * A stack as one read of its pile found it.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param id       the item id
     * @param quantity the stack size
     * @param tile     the tile it lies on
     */
    public TileItem(int id, int quantity, Tile tile) {
        this.id = id;
        this.quantity = quantity;
        this.tile = tile;
    }

    /**
     * Whether a Lua plugin has hidden the item. No Lua function hides ground items, so this is false.
     *
     * @return false
     */
    public boolean isHidden() {
        return false;
    }

    @Override
    public String toString() {
        return "TileItem[" + id + " x" + quantity + "]";
    }
}
