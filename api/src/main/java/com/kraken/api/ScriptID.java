package com.kraken.api;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Ids of client scripts (cs2) that plugins watch for in {@code ScriptPreFired} and {@code ScriptPostFired}, by what
 * each does.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScriptID {

    /**
     * Sets up the deposit box interface.
     */
    public static final int BANK_DEPOSITBOX_INIT = 144;

    /**
     * Draws one item slot of an interface's own view of the inventory, such as the one beside the equipment stats.
     * The engine's active widget is the slot when it returns.
     */
    public static final int INTERFACE_INV_DRAW_SLOT_BIG = 154;

    /**
     * Draws the items of the Chambers of Xeric private storage.
     */
    public static final int RAIDS_STORAGE_PRIVATE_ITEMS = 1607;

    /**
     * Builds the seed vault's lists of seeds.
     */
    public static final int SEED_VAULT_BUILD = 2819;

    /**
     * Draws one item slot of the inventory or the bank. The engine's active widget is the slot when it returns.
     */
    public static final int INVENTORY_DRAWITEM = 6011;
}
