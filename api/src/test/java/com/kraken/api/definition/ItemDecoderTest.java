package com.kraken.api.definition;

import com.kraken.api.ConfigFile;
import com.kraken.api.internal.ConfigFormatException;
import com.kraken.api.internal.ItemDecoder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemDecoder} against hand-built files in the 241.3 client's format (32-bit model opcodes, conditional ops),
 * checking the loader rules taken from the client: defaults, noted/bought/placeholder merges and the free-world
 * rewrite of members items.
 */
class ItemDecoderTest {

    private static final int LOGS = 1511;
    private static final int LOGS_NOTED = 1512;
    private static final int NOTE_TEMPLATE = 799;
    private static final int WHIP = 4151;
    private static final int WHIP_NOTED = 4152;
    private static final int LOGS_PLACEHOLDER = 14_000;
    private static final int PLACEHOLDER_TEMPLATE = 14_401;
    private static final int LOGS_BOUGHT = 15_000;
    private static final int BOUGHT_TEMPLATE = 15_001;

    private final Map<Integer, byte[]> files = new HashMap<>();
    private final IntFunction<byte[]> archive = files::get;

    /**
     * Lays out the item files the tests load: logs with their note, placeholder and bought variants, and a members
     * whip with its note.
     */
    ItemDecoderTest() {
        files.put(LOGS, new ConfigFile()
                .op(44).g4(2704)
                .op(2).str("Logs")
                .op(12).g4(4)
                .op(65)
                .op(75).g2(2000)
                .op(97).g2(LOGS_NOTED)
                .op(148).g2(LOGS_PLACEHOLDER)
                .op(30).str("Take")
                .op(36).str("Light")
                .end());
        files.put(LOGS_NOTED, new ConfigFile().op(97).g2(LOGS).op(98).g2(NOTE_TEMPLATE).end());
        files.put(NOTE_TEMPLATE, new ConfigFile().op(44).g4(2429).op(2).str("null").end());
        files.put(WHIP, new ConfigFile()
                .op(44).g4(5412)
                .op(2).str("Abyssal whip")
                .op(3).str("A weapon from the abyss.")
                .op(12).g4(120_001)
                .op(13).g1(3)
                .op(16)
                .op(65)
                .op(35).str("Wield")
                .op(94).g2(1)
                .end());
        files.put(WHIP_NOTED, new ConfigFile().op(97).g2(WHIP).op(98).g2(NOTE_TEMPLATE).end());
        files.put(LOGS_PLACEHOLDER, new ConfigFile().op(148).g2(LOGS).op(149).g2(PLACEHOLDER_TEMPLATE).end());
        files.put(PLACEHOLDER_TEMPLATE, new ConfigFile().op(44).g4(38_000).op(94).g2(7).end());
        files.put(LOGS_BOUGHT, new ConfigFile().op(139).g2(LOGS).op(140).g2(BOUGHT_TEMPLATE).end());
        files.put(BOUGHT_TEMPLATE, new ConfigFile().op(44).g4(39_000).end());
    }

    /**
     * Loads an item with no client, so no vars resolve.
     *
     * @param id           the item id
     * @param membersWorld whether the world is a members world
     * @return the item's definition
     */
    private ItemComposition load(int id, boolean membersWorld) {
        return ItemDecoder.load(id, archive, membersWorld, null);
    }

    @Test
    void idWithoutFileIsTheDefaultType() {
        ItemComposition none = load(99_999, true);
        assertEquals(99_999, none.getId());
        assertEquals("", none.getName());
        assertEquals(1, none.getPrice());
        assertFalse(none.isStackable());
        assertEquals(List.of("", "", "Take", "", ""), none.getIOps(null, null));
        assertEquals(List.of("", "", "", "", "Drop"), none.getOps());
        assertEquals("Take", none.groundOp(2, null, null));
        assertEquals("", none.groundOp(3, null, null));
        assertEquals(-1, none.getCanonicalId());
        assertEquals(-1, none.getSlot());
        assertEquals(-1, none.getLinkedNoteId());
    }

    @Test
    void decodesPlainItem() {
        ItemComposition logs = load(LOGS, true);
        assertEquals("Logs", logs.getName());
        assertEquals(2704, logs.getInventoryModel());
        assertEquals(4, logs.getPrice());
        assertEquals(2000, logs.getWeight());
        assertTrue(logs.isTradeable());
        assertEquals(LOGS_NOTED, logs.getLinkedNoteId());
        assertEquals(LOGS_PLACEHOLDER, logs.getPlaceholderId());
        assertEquals(LOGS, logs.getCanonicalId());
        assertEquals(List.of("", "Light", "", "", "Drop"), logs.getOps());
        assertEquals(List.of("Take", "", "Take", "", ""), logs.getIOps(null, null));
    }

    @Test
    void stackableItemsWeighNothing() {
        files.put(995, new ConfigFile().op(2).str("Coins").op(11).op(75).g2(10).end());
        ItemComposition coins = load(995, true);
        assertTrue(coins.isStackable());
        assertEquals(0, coins.getWeight());
    }

    @Test
    void stackMode2IsNotStackable() {
        files.put(30, new ConfigFile().op(160).op(75).g2(10).end());
        ItemComposition type = load(30, true);
        assertFalse(type.isStackable());
        assertEquals(10, type.getWeight());
    }

    @Test
    void notedItemTakesNameAndValueFromTheItem() {
        ItemComposition noted = load(LOGS_NOTED, true);
        assertEquals("Logs", noted.getName());
        assertEquals(ItemDecoder.NOTE_DESC, noted.getExamine());
        assertEquals(2429, noted.getInventoryModel());
        assertEquals(4, noted.getPrice());
        assertTrue(noted.isStackable());
        assertEquals(LOGS, noted.getLinkedNoteId());
        assertEquals(LOGS, noted.getCanonicalId());
        assertFalse(noted.isTradeable(), "tradeable isn't copied from the item");
    }

    @Test
    void placeholderLooksLikeItsTemplate() {
        ItemComposition placeholder = load(LOGS_PLACEHOLDER, true);
        assertEquals("Logs", placeholder.getName());
        assertEquals(38_000, placeholder.getInventoryModel());
        assertEquals(0, placeholder.getPrice());
        assertEquals(7, placeholder.getCategory());
        assertFalse(placeholder.isTradeable());
        assertEquals(LOGS, placeholder.getPlaceholderId());
        assertEquals(LOGS, placeholder.getCanonicalId());
    }

    @Test
    void boughtItemIsWorthNothingAndDiscards() {
        ItemComposition bought = load(LOGS_BOUGHT, true);
        assertEquals("Logs", bought.getName());
        assertEquals(39_000, bought.getInventoryModel());
        assertEquals(0, bought.getPrice());
        assertEquals(2000, bought.getWeight());
        assertEquals(List.of("", "Light", "", "", "Discard"), bought.getOps());
        assertEquals(List.of("Take", "", "Take", "", ""), bought.getIOps(null, null));
        assertEquals(LOGS_BOUGHT, bought.getCanonicalId(), "the client doesn't resolve bought items");
    }

    @Test
    void membersItemOnMembersWorldIsUntouched() {
        ItemComposition whip = load(WHIP, true);
        assertEquals("Abyssal whip", whip.getName());
        assertEquals("A weapon from the abyss.", whip.getExamine());
        assertEquals(List.of("Wield", "", "", "", "Drop"), whip.getOps());
        assertTrue(whip.isMembers());
        assertTrue(whip.isEquipable());
        assertEquals(3, whip.getSlot());
        assertEquals(1, whip.getCategory());
    }

    @Test
    void membersItemOnFreeWorldIsRewritten() {
        ItemComposition whip = load(WHIP, false);
        assertEquals("Abyssal whip (Members)", whip.getName());
        assertEquals(ItemDecoder.MEMBERS_DESC, whip.getExamine());
        assertEquals(List.of("", "", "", "", "Drop"), whip.getOps());
        assertEquals(List.of("", "", "Take", "", ""), whip.getIOps(null, null));
        assertEquals(-1, whip.getCategory());
        assertEquals(120_001, whip.getPrice());
        assertTrue(whip.isTradeable(), "only the flag beside tradeable is cleared");
    }

    @Test
    void notedMembersItemOnFreeWorldIsNotSuffixedTwice() {
        ItemComposition noted = load(WHIP_NOTED, false);
        assertEquals("Abyssal whip (Members)", noted.getName(), "the name comes from the rewritten whip");
        assertEquals(ItemDecoder.MEMBERS_DESC, noted.getExamine());
        assertTrue(noted.isMembers());
    }

    @Test
    void hiddenOpLeavesTheSlotAsItWas() {
        files.put(40, new ConfigFile().op(32).str("Hidden").op(30).str("hidden").op(31).str("Open").end());
        ItemComposition type = load(40, true);
        assertEquals(List.of("", "Open", "Take", "", ""), type.getIOps(null, null));
    }

    @Test
    void conditionalOpsFollowVars() {
        files.put(41, new ConfigFile()
                .op(30).str("Open")
                .op(201).g1(0).g2(100).g2(0xffff).g4(1).g4(1).str("Close")
                .op(31).str("Look")
                .op(201).g1(1).g2(0).g2(500).g4(2).g4(5).str("Peek")
                .end());
        ItemComposition type = load(41, true);
        Map<Integer, Integer> varps = new HashMap<>();
        Map<Integer, Integer> varbits = new HashMap<>();
        IntUnaryOperator varp = id -> varps.getOrDefault(id, 0);
        IntUnaryOperator varbit = id -> varbits.getOrDefault(id, 0);
        assertEquals("Open", type.groundOp(0, varp, varbit));
        varps.put(100, 1);
        assertEquals("Close", type.groundOp(0, varp, varbit));
        varbits.put(500, 3);
        assertEquals(List.of("Close", "Peek", "Take", "", ""), type.getIOps(varp, varbit));
        assertEquals(List.of("Open", "Look", "Take", "", ""), type.getIOps(null, null));
    }

    @Test
    void settingAnOpDropsItsConditions() {
        files.put(42, new ConfigFile()
                .op(201).g1(0).g2(1).g2(0xffff).g4(0).g4(0).str("Cond")
                .op(30).str("Plain")
                .end());
        ItemComposition type = load(42, true);
        assertEquals("Plain", type.groundOp(0, id -> 0, id -> 0));
    }

    @Test
    void readsParamsAndSkipsUnusedOpcodes() {
        files.put(43, new ConfigFile()
                .op(4).g2(2000).op(7).g2(65_000).op(40).g1(1).g2(1).g2(2).op(43).g1(0).g1(1).str("Sub").g1(0)
                .op(45).g4(1).g1(2).op(100).g2(1).g2(2).op(113).g1(1).op(161).g2(2).g2(1).g2(2)
                .op(200).g1(0).g1(1).str("x").op(202).g1(0).g2(1).g2(2).g2(3).g4(4).g4(5).str("y").op(251)
                .op(249).g1(2).g1(0).g3(10).g4(42).g1(1).g3(11).str("text")
                .op(2).str("Kitchen sink")
                .end());
        ItemComposition type = load(43, true);
        assertEquals("Kitchen sink", type.getName());
        assertEquals(42, type.getIntValue(10));
        assertEquals("text", type.getStringValue(11));
    }

    @Test
    void twoHandedNeedsWeaponAndShieldSlots() {
        files.put(44, new ConfigFile().op(13).g1(3).op(14).g1(5).end());
        assertTrue(load(44, true).isTwoHanded());
        files.put(45, new ConfigFile().op(13).g1(3).end());
        assertFalse(load(45, true).isTwoHanded());
    }

    @Test
    void unknownOpcodeFails() {
        files.put(46, new ConfigFile().op(1).g2(5).end());
        assertThrows(ConfigFormatException.class, () -> load(46, true));
    }

    @Test
    void templateCycleFails() {
        files.put(47, new ConfigFile().op(97).g2(48).op(98).g2(48).end());
        files.put(48, new ConfigFile().op(97).g2(47).op(98).g2(47).end());
        assertThrows(ConfigFormatException.class, () -> load(47, true));
    }
}
