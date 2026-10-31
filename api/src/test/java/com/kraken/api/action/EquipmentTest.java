package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Equipment}: the reads from the worn-items table, and the ops of the worn-items interface's slot components,
 * which carry the worn item's id.
 */
class EquipmentTest {

    private static final int WORN = InterfaceID.Wornitems.UNIVERSE >>> 16;
    private static final int WEAPON_SLOT = InterfaceID.Wornitems.SLOT3;
    private static final int RING_SLOT = InterfaceID.Wornitems.SLOT12;
    private static final int WHIP = 4151;
    private static final int RING = 2566;
    private static final int COINS = 995;

    private GameFixture game;
    private Client client;
    private Equipment equipment;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        equipment = client.getEquipment();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
        game.setInvSlot(94, 12, RING, 1);
    }

    /**
     * Builds the worn-items interface: a whip in slot 3 with Remove and Examine, and a ring of dueling in slot 12 with
     * Remove and Teleport, whose Teleport has two sub-options.
     */
    private void loadWornInterface() {
        long[] children = new long[25];
        for (int i = 0; i < children.length; i++) {
            children[i] = game.addComponent((WORN << 16) | i, -1, -1, -1, WidgetType.LAYER, 0, 0, 0, 0);
        }
        children[18] = wornSlot(WEAPON_SLOT, WHIP, "Remove", "Examine");
        children[24] = wornSlot(RING_SLOT, RING, "Remove", "Teleport");
        game.setSubOps(children[24], 2, "Duel Arena", "Castle Wars");
        game.setGroup(WORN, children);
    }

    private long wornSlot(int packedId, int item, String... ops) {
        long component = game.addComponent(packedId, -1, -1, -1, WidgetType.LAYER, 0, 0, 36, 32);
        game.memory.putInt(component + game.map.offset("IfType.linkObjType"), item);
        game.setOps(component, ops);
        return component;
    }

    @Test
    void readsComeFromTheWornTable() {
        assertTrue(equipment.isEquipped(WHIP));
        assertTrue(equipment.isEquipped(RING));
        assertFalse(equipment.isEquipped(COINS));
        assertEquals(WHIP, equipment.getItem(EquipmentInventorySlot.WEAPON).getId());
        assertEquals(1, equipment.getItem(EquipmentInventorySlot.WEAPON).getQuantity());
        assertNull(equipment.getItem(EquipmentInventorySlot.HEAD), "empty slot");
    }

    @Test
    void anEmptySlotIsNotFound() {
        loadWornInterface();
        assertEquals(ActionResult.NOT_FOUND, equipment.remove(EquipmentInventorySlot.HEAD));
        assertEquals(ActionResult.NOT_FOUND, equipment.interact(EquipmentInventorySlot.HEAD, "Remove"));
        assertEquals(ActionResult.NOT_FOUND, equipment.interact(EquipmentInventorySlot.HEAD, "Remove", "Duel Arena"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSlotNeedsTheWornInterfaceLoaded() {
        assertEquals(ActionResult.NOT_FOUND, equipment.remove(EquipmentInventorySlot.WEAPON));
        assertEquals(ActionResult.NOT_FOUND, equipment.interact(EquipmentInventorySlot.WEAPON, "Examine"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void removeRunsTheSlotsRemove() {
        loadWornInterface();
        assertEquals(ActionResult.SENT, equipment.remove(EquipmentInventorySlot.WEAPON));
        reference.menuAction(-1, WEAPON_SLOT, MenuAction.CC_OP, 1, WHIP);
        assertEquals(expected, sent);
    }

    @Test
    void interactRunsAnyOpOfTheWornItem() {
        loadWornInterface();
        assertEquals(ActionResult.SENT, equipment.interact(EquipmentInventorySlot.WEAPON, "examine"));
        reference.menuAction(-1, WEAPON_SLOT, MenuAction.CC_OP, 2, WHIP);
        assertEquals(expected, sent);
        assertEquals(ActionResult.NO_SUCH_OPTION, equipment.interact(EquipmentInventorySlot.WEAPON, "Rub"));
        assertEquals(1, sent.size(), "the missing op sends nothing");
    }

    @Test
    void interactRunsAnOpWithItsSubOption() {
        loadWornInterface();
        assertEquals(ActionResult.SENT, equipment.interact(EquipmentInventorySlot.RING, "Teleport", "castle wars"));
        reference.menuAction(-1, RING_SLOT, MenuAction.CC_OP, 2, RING, 2);
        assertEquals(expected, sent);
        assertEquals(ActionResult.NO_SUCH_OPTION,
                equipment.interact(EquipmentInventorySlot.RING, "Teleport", "Ferox Enclave"));
        assertEquals(1, sent.size(), "the missing sub-option sends nothing");
    }
}
