package com.kraken.api;

/**
 * A slot of the worn-equipment inventory ({@code InventoryID.WORN}), as RuneLite's {@code EquipmentInventorySlot}. The
 * slot numbers are the client's, the same as Lua's {@code ClientOp.WornSlots}.
 */
public enum EquipmentInventorySlot {
    HEAD(0),
    CAPE(1),
    AMULET(2),
    WEAPON(3),
    BODY(4),
    SHIELD(5),
    LEGS(7),
    GLOVES(9),
    BOOTS(10),
    RING(12),
    AMMO(13);

    private final int slotIdx;

    EquipmentInventorySlot(int slotIdx) {
        this.slotIdx = slotIdx;
    }

    /**
     * The slot's index in the worn-equipment inventory.
     *
     * @return the slot index
     */
    public int getSlotIdx() {
        return slotIdx;
    }
}
