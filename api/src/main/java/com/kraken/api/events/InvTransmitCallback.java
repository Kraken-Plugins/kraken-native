package com.kraken.api.events;

import com.kraken.api.game.Inventory;

import java.util.List;

/**
 * Mirrors the Lua {@code OnInvTransmitCallback}: {@code (inv_table) => void}, called for
 * {@link Events#ON_INV_TRANSMIT} with every inventory that changed, each listing only its changed slots.
 */
@FunctionalInterface
public interface InvTransmitCallback {
    void onInvTransmit(List<Inventory> inventories);
}
