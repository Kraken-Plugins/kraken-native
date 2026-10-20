package com.kraken.api;

/**
 * An item stack lying on the ground, as RuneLite's {@code TileItem}. Kraken reads the whole pile on a tile
 * ({@link Tile#getGroundItems()}), so RuneLite's {@code ItemLayer} isn't needed. The despawn time, ownership and
 * privacy aren't read.
 *
 * <p>A handle with identity: the item spawn events carry one handle per stack, the same in its spawn, quantity change
 * and despawn events, and its quantity follows {@code ItemQuantityChanged}. {@link Tile#getGroundItems()} makes new
 * handles each call, so compare those by id and tile.
 */
public interface TileItem extends Renderable {

    /**
     * The item's id.
     *
     * @return the item id, as in {@code gameval.ItemID}
     */
    int getId();

    /**
     * The stack's size.
     *
     * @return the quantity, as last read
     */
    int getQuantity();

    /**
     * Whether a Lua plugin has hidden the item. Kraken extra: no Lua function hides ground items, so this is false.
     *
     * @return false
     */
    @Override
    boolean isHidden();
}
