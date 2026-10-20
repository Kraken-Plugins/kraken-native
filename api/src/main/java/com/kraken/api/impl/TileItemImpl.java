package com.kraken.api.impl;

import com.kraken.api.TileItem;

/**
 * {@link TileItem}: an item id and a quantity, with identity. The event source keeps one per stack across reads and
 * updates its quantity; {@code Tile.getGroundItems()} makes new ones.
 */
final class TileItemImpl implements TileItem {

    private final int id;
    private volatile int quantity;

    TileItemImpl(int id, int quantity) {
        this.id = id;
        this.quantity = quantity;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public int getQuantity() {
        return quantity;
    }

    /**
     * Records a new quantity for the stack.
     *
     * @param quantity the quantity now
     */
    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public String toString() {
        return "TileItem[" + id + " x" + quantity + "]";
    }
}
