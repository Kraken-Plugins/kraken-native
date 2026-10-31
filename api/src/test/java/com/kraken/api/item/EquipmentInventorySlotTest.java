package com.kraken.api.item;

import com.kraken.api.gameval.InterfaceID;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each worn slot's component is the worn-items interface's {@code SLOT<n>} for its slot index.
 */
class EquipmentInventorySlotTest {

    @Test
    void componentIsTheWornItemsSlotOfTheSameNumber() throws ReflectiveOperationException {
        Set<Integer> components = new HashSet<>();
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values()) {
            int expected = InterfaceID.Wornitems.class.getField("SLOT" + slot.getSlotIdx()).getInt(null);
            assertEquals(expected, slot.getComponentId(), slot.name());
            assertEquals(InterfaceID.Wornitems.UNIVERSE >>> 16, slot.getComponentId() >>> 16, slot.name());
            assertTrue(components.add(slot.getComponentId()), slot.name() + " shares a component");
        }
    }
}
