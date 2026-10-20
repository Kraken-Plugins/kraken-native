package com.kraken.api.gameval;

/**
 * Inventory ids, named as in RuneLite's {@code gameval.InventoryID}. The ids are cache ids, the same in the native
 * client. Hand-written for the inventories Kraken already uses; the full generated set comes later.
 */
public final class InventoryID {

    /**
     * The backpack.
     */
    public static final int INV = 93;

    /**
     * Worn equipment; slots are {@code EquipmentInventorySlot}s.
     */
    public static final int WORN = 94;

    /**
     * The bank.
     */
    public static final int BANK = 95;

    private InventoryID() {
    }
}
