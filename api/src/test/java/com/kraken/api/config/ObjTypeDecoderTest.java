package com.kraken.api.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjTypeDecoder} against hand-built files in the 241.3 client's format (32-bit model opcodes, conditional ops),
 * checking the loader rules taken from the client: defaults, noted/bought/placeholder merges and the free-world
 * rewrite of members items.
 */
class ObjTypeDecoderTest {

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

    ObjTypeDecoderTest() {
        files.put(LOGS, new File()
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
        files.put(LOGS_NOTED, new File().op(97).g2(LOGS).op(98).g2(NOTE_TEMPLATE).end());
        files.put(NOTE_TEMPLATE, new File().op(44).g4(2429).op(2).str("null").end());
        files.put(WHIP, new File()
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
        files.put(WHIP_NOTED, new File().op(97).g2(WHIP).op(98).g2(NOTE_TEMPLATE).end());
        files.put(LOGS_PLACEHOLDER, new File().op(148).g2(LOGS).op(149).g2(PLACEHOLDER_TEMPLATE).end());
        files.put(PLACEHOLDER_TEMPLATE, new File().op(44).g4(38_000).op(94).g2(7).end());
        files.put(LOGS_BOUGHT, new File().op(139).g2(LOGS).op(140).g2(BOUGHT_TEMPLATE).end());
        files.put(BOUGHT_TEMPLATE, new File().op(44).g4(39_000).end());
    }

    @Test
    void idWithoutFileIsTheDefaultType() {
        ObjType none = ObjTypeDecoder.load(99_999, archive, true);
        assertEquals(99_999, none.getId());
        assertEquals("", none.getName());
        assertEquals(1, none.getValue());
        assertFalse(none.isStackable());
        assertEquals(List.of("", "", "Take", "", ""), none.getIOps());
        assertEquals(List.of("", "", "", "", "Drop"), none.getOps());
        assertEquals("Take", none.groundOp(2, null));
        assertEquals("", none.groundOp(3, null));
        assertEquals(-1, none.getCanonicalId());
        assertEquals(-1, none.getSlot());
        assertEquals(-1, none.getCertId());
    }

    @Test
    void decodesPlainItem() {
        ObjType logs = ObjTypeDecoder.load(LOGS, archive, true);
        assertEquals("Logs", logs.getName());
        assertEquals(2704, logs.getModelId());
        assertEquals(4, logs.getValue());
        assertEquals(2000, logs.getWeight());
        assertTrue(logs.isTradeable());
        assertEquals(LOGS_NOTED, logs.getCertId());
        assertEquals(LOGS_PLACEHOLDER, logs.getPlaceholderId());
        assertEquals(LOGS, logs.getCanonicalId());
        assertEquals(List.of("", "Light", "", "", "Drop"), logs.getOps());
        assertEquals(List.of("Take", "", "Take", "", ""), logs.getIOps());
        assertEquals(TypecodeEntityType.OBJ, logs.getEntityType());
    }

    @Test
    void stackableItemsWeighNothing() {
        files.put(995, new File().op(2).str("Coins").op(11).op(75).g2(10).end());
        ObjType coins = ObjTypeDecoder.load(995, archive, true);
        assertTrue(coins.isStackable());
        assertEquals(0, coins.getWeight());
    }

    @Test
    void stackMode2IsNotStackable() {
        files.put(30, new File().op(160).op(75).g2(10).end());
        ObjType type = ObjTypeDecoder.load(30, archive, true);
        assertFalse(type.isStackable());
        assertEquals(10, type.getWeight());
    }

    @Test
    void notedItemTakesNameAndValueFromTheItem() {
        ObjType noted = ObjTypeDecoder.load(LOGS_NOTED, archive, true);
        assertEquals("Logs", noted.getName());
        assertEquals(ObjTypeDecoder.NOTE_DESC, noted.getDesc());
        assertEquals(2429, noted.getModelId());
        assertEquals(4, noted.getValue());
        assertTrue(noted.isStackable());
        assertEquals(LOGS, noted.getCertId());
        assertEquals(LOGS, noted.getCanonicalId());
        assertFalse(noted.isTradeable(), "tradeable isn't copied from the item");
    }

    @Test
    void placeholderLooksLikeItsTemplate() {
        ObjType placeholder = ObjTypeDecoder.load(LOGS_PLACEHOLDER, archive, true);
        assertEquals("Logs", placeholder.getName());
        assertEquals(38_000, placeholder.getModelId());
        assertEquals(0, placeholder.getValue());
        assertEquals(7, placeholder.getCategory());
        assertFalse(placeholder.isTradeable());
        assertEquals(LOGS, placeholder.getPlaceholderId());
        assertEquals(LOGS, placeholder.getCanonicalId());
    }

    @Test
    void boughtItemIsWorthNothingAndDiscards() {
        ObjType bought = ObjTypeDecoder.load(LOGS_BOUGHT, archive, true);
        assertEquals("Logs", bought.getName());
        assertEquals(39_000, bought.getModelId());
        assertEquals(0, bought.getValue());
        assertEquals(2000, bought.getWeight());
        assertEquals(List.of("", "Light", "", "", "Discard"), bought.getOps());
        assertEquals(List.of("Take", "", "Take", "", ""), bought.getIOps());
        assertEquals(LOGS_BOUGHT, bought.getCanonicalId(), "the client doesn't resolve bought items");
    }

    @Test
    void membersItemOnMembersWorldIsUntouched() {
        ObjType whip = ObjTypeDecoder.load(WHIP, archive, true);
        assertEquals("Abyssal whip", whip.getName());
        assertEquals("A weapon from the abyss.", whip.getDesc());
        assertEquals(List.of("Wield", "", "", "", "Drop"), whip.getOps());
        assertTrue(whip.isMembers());
        assertTrue(whip.isEquipable());
        assertEquals(3, whip.getSlot());
        assertEquals(1, whip.getCategory());
    }

    @Test
    void membersItemOnFreeWorldIsRewritten() {
        ObjType whip = ObjTypeDecoder.load(WHIP, archive, false);
        assertEquals("Abyssal whip (Members)", whip.getName());
        assertEquals(ObjTypeDecoder.MEMBERS_DESC, whip.getDesc());
        assertEquals(List.of("", "", "", "", "Drop"), whip.getOps());
        assertEquals(List.of("", "", "Take", "", ""), whip.getIOps());
        assertEquals(-1, whip.getCategory());
        assertEquals(120_001, whip.getValue());
        assertTrue(whip.isTradeable(), "only the flag beside tradeable is cleared");
    }

    @Test
    void notedMembersItemOnFreeWorldIsNotSuffixedTwice() {
        ObjType noted = ObjTypeDecoder.load(WHIP_NOTED, archive, false);
        assertEquals("Abyssal whip (Members)", noted.getName(), "the name comes from the rewritten whip");
        assertEquals(ObjTypeDecoder.MEMBERS_DESC, noted.getDesc());
        assertTrue(noted.isMembers());
    }

    @Test
    void hiddenOpLeavesTheSlotAsItWas() {
        files.put(40, new File().op(32).str("Hidden").op(30).str("hidden").op(31).str("Open").end());
        ObjType type = ObjTypeDecoder.load(40, archive, true);
        assertEquals(List.of("", "Open", "Take", "", ""), type.getIOps());
    }

    @Test
    void conditionalOpsFollowVars() {
        files.put(41, new File()
                .op(30).str("Open")
                .op(201).g1(0).g2(100).g2(0xffff).g4(1).g4(1).str("Close")
                .op(31).str("Look")
                .op(201).g1(1).g2(0).g2(500).g4(2).g4(5).str("Peek")
                .end());
        ObjType type = ObjTypeDecoder.load(41, archive, true);
        Vars vars = new Vars();
        assertEquals("Open", type.groundOp(0, vars));
        vars.varps.put(100, 1);
        assertEquals("Close", type.groundOp(0, vars));
        vars.varbits.put(500, 3);
        assertEquals(List.of("Close", "Peek", "Take", "", ""), type.getIOps(vars));
        assertEquals(List.of("Open", "Look", "Take", "", ""), type.getIOps());
        assertEquals(-1, type.getGroundOps().get(0).conditions().get(0).varbit());
    }

    @Test
    void settingAnOpDropsItsConditions() {
        files.put(42, new File()
                .op(201).g1(0).g2(1).g2(0xffff).g4(0).g4(0).str("Cond")
                .op(30).str("Plain")
                .end());
        ObjType type = ObjTypeDecoder.load(42, archive, true);
        assertEquals("Plain", type.groundOp(0, new Vars()));
    }

    @Test
    void readsParamsAndSkipsUnusedOpcodes() {
        files.put(43, new File()
                .op(4).g2(2000).op(7).g2(65_000).op(40).g1(1).g2(1).g2(2).op(43).g1(0).g1(1).str("Sub").g1(0)
                .op(45).g4(1).g1(2).op(100).g2(1).g2(2).op(113).g1(1).op(161).g2(2).g2(1).g2(2)
                .op(200).g1(0).g1(1).str("x").op(202).g1(0).g2(1).g2(2).g2(3).g4(4).g4(5).str("y").op(251)
                .op(249).g1(2).g1(0).g3(10).g4(42).g1(1).g3(11).str("text")
                .op(2).str("Kitchen sink")
                .end());
        ObjType type = ObjTypeDecoder.load(43, archive, true);
        assertEquals("Kitchen sink", type.getName());
        assertEquals(Map.of(10, 42, 11, "text"), type.getParams());
    }

    @Test
    void twoHandedNeedsWeaponAndShieldSlots() {
        files.put(44, new File().op(13).g1(3).op(14).g1(5).end());
        assertTrue(ObjTypeDecoder.load(44, archive, true).isTwoHanded());
        files.put(45, new File().op(13).g1(3).end());
        assertFalse(ObjTypeDecoder.load(45, archive, true).isTwoHanded());
    }

    @Test
    void unknownOpcodeFails() {
        files.put(46, new File().op(1).g2(5).end());
        assertThrows(ConfigFormatException.class, () -> ObjTypeDecoder.load(46, archive, true));
    }

    @Test
    void templateCycleFails() {
        files.put(47, new File().op(97).g2(48).op(98).g2(48).end());
        files.put(48, new File().op(97).g2(47).op(98).g2(47).end());
        assertThrows(ConfigFormatException.class, () -> ObjTypeDecoder.load(47, archive, true));
    }

    private static final class Vars implements ObjType.VarReader {
        final Map<Integer, Integer> varps = new HashMap<>();
        final Map<Integer, Integer> varbits = new HashMap<>();

        @Override
        public int varp(int id) {
            return varps.getOrDefault(id, 0);
        }

        @Override
        public int varbit(int id) {
            return varbits.getOrDefault(id, 0);
        }
    }

    /**
     * Builds a config file: opcodes and big-endian operands, ended by opcode 0.
     */
    private static final class File {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        File op(int opcode) {
            return g1(opcode);
        }

        File g1(int v) {
            out.write(v);
            return this;
        }

        File g2(int v) {
            return g1(v >> 8).g1(v);
        }

        File g3(int v) {
            return g1(v >> 16).g2(v);
        }

        File g4(int v) {
            return g2(v >> 16).g2(v);
        }

        File str(String s) {
            out.writeBytes(s.getBytes(Charset.forName("windows-1252")));
            return g1(0);
        }

        byte[] end() {
            out.write(0);
            return out.toByteArray();
        }
    }
}
