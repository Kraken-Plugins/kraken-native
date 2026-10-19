package com.kraken.api.game;

/**
 * One slot of an inventory, with the same fields as the item tables the Lua {@code ON_INV_TRANSMIT} event carries.
 *
 * @param id    the item id in the slot, or -1 if it's empty
 * @param slot  the slot's index in its inventory
 * @param count the stack size (0 when empty)
 */
public record InvItem(int id, int slot, int count) {

    public boolean isEmpty() {
        return id == -1;
    }
}
