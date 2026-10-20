package com.kraken.api.lua;

import java.util.List;

/**
 * An inventory as the client holds it (a snapshot), shaped like the tables the Lua {@code ON_INV_TRANSMIT} event
 * carries. From {@link ClientOp#getInventory} every slot the client holds is listed, empty ones included; in an
 * {@code ON_INV_TRANSMIT} event only the slots that changed are.
 *
 * @param id    the inventory id ({@link ClientOp#INV_BACKPACK}, {@link ClientOp#INV_WORN}, …)
 * @param items the slots, in slot order
 */
public record Inventory(int id, List<InvItem> items) {
}
