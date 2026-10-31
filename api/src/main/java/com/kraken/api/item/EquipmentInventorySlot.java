package com.kraken.api.item;

import com.kraken.api.gameval.InterfaceID;
import lombok.Getter;

/**
 * A slot of the worn-equipment inventory ({@code InventoryID.WORN}). The slot numbers are the client's, the same as
 * Lua's {@code ClientOp.WornSlots}.
 */
@Getter
public enum EquipmentInventorySlot {
    HEAD(0, InterfaceID.Wornitems.SLOT0),
    CAPE(1, InterfaceID.Wornitems.SLOT1),
    AMULET(2, InterfaceID.Wornitems.SLOT2),
    WEAPON(3, InterfaceID.Wornitems.SLOT3),
    BODY(4, InterfaceID.Wornitems.SLOT4),
    SHIELD(5, InterfaceID.Wornitems.SLOT5),
    LEGS(7, InterfaceID.Wornitems.SLOT7),
    GLOVES(9, InterfaceID.Wornitems.SLOT9),
    BOOTS(10, InterfaceID.Wornitems.SLOT10),
    RING(12, InterfaceID.Wornitems.SLOT12),
    AMMO(13, InterfaceID.Wornitems.SLOT13);

    /**
     * The slot's index in the worn-equipment inventory.
     */
    private final int slotIdx;

    /**
     * The packed id of the slot's component on the worn-items interface ({@code InterfaceID.Wornitems.SLOT<n>}, n being
     * {@link #getSlotIdx()}): the component that shows the worn item and that the client's menu rows for it ("Remove",
     * a teleport's destinations) name.
     */
    private final int componentId;

    EquipmentInventorySlot(int slotIdx, int componentId) {
        this.slotIdx = slotIdx;
        this.componentId = componentId;
    }
}
