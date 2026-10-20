package com.kraken.api.definition;

import com.kraken.api.internal.ConfigFormatException;
import com.kraken.api.internal.NpcDecoder;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes real NPC type files (241.3's config group 9, byte-for-byte as the client holds them in memory) and checks
 * the fields the client's own decoder would produce.
 */
class NpcDecoderTest {

    /**
     * Goblin: name, size, animations, one op, category, combat level, stats, models and params.
     */
    private static final String GOBLIN = "0c010d18250e18244a00014b00014c00014d00051f41747461636b0012013762007d61007d640f"
            + "5f000202476f626c696e003d0600005f8700005fa500005f6f00005f9800005f7900005f803e0100005e91f9070000001a000000"
            + "020000000afffffff100000005fffffff100000006fffffff100000007fffffff100000008fffffff1000000320000000200";

    /**
     * Mortimer: ops 3-5 have a variant with no text while varbit 15785 is 0 (opcode 252).
     */
    private static final String MORTIMER = "1e54616c6b2d746f002041737369676e6d656e740021547261646500225265776172647300fc0"
            + "2ffff3da9000000000000000000fc03ffff3da9000000000000000000fc04ffff3da90000000000000000005f0000024d6f7274"
            + "696d6572003d010000f1603e010000f14d00";

    /**
     * Rod fishing spot: op 1 "Lure", op 3 "Bait", ops 4 and 5 "hidden" (which leaves them unset).
     */
    private static final String FISHING_SPOT = "5f00000d1dd20e1dd20c015d1e4c757265002042616974002168696464656e002268696464"
            + "656e0012011802526f642046697368696e672073706f74003d010000a11600";

    /**
     * A multi-NPC (opcode 118): varbit 11875 picks 10621, anything else falls back to 10622. Size 3, no name.
     */
    private static final String MULTI = "5f00000c0312039a762e63ffff297e00297d00";

    /**
     * Mother: a head icon (opcode 102) and 64-bit-free params.
     */
    private static final String HEAD_ICON = "0c030d1fda0e1fd64a00964c00964e00694f003c4b00aa4d00eb1f41747461636b00120501635f"
            + "00c6660101b801024d6f74686572003d0100008bf63e0100008bfef9030000000e00000004000000040000003200000008000000c8"
            + "00";

    /**
     * Decodes an NPC file with no client, so no vars resolve.
     *
     * @param id  the NPC type id
     * @param hex the file's bytes, in hex
     * @return the decoded type
     */
    private static NPCComposition decode(int id, String hex) {
        return NpcDecoder.decode(id, HexFormat.of().parseHex(hex), null);
    }

    @Test
    void decodesGoblin() {
        NPCComposition goblin = decode(3029, GOBLIN);
        assertEquals(3029, goblin.getId());
        assertEquals("Goblin", goblin.getName());
        assertEquals(1, goblin.getSize());
        assertEquals(311, goblin.getCategory());
        assertEquals(2, goblin.getCombatLevel());
        assertEquals(List.of("", "Attack", "", "", ""), goblin.getOps());
        assertArrayEquals(new int[]{1, 1, 1, 5, 1, 1}, goblin.getStats());
        assertEquals(6181, goblin.getStandingAnimation());
        assertEquals(6180, goblin.getWalkingAnimation());
        assertEquals(6, goblin.getModels().length);
        assertEquals(Map.of(26, 2, 10, -15, 5, -15, 6, -15, 7, -15, 8, -15, 50, 2), goblin.getParams());
        assertEquals(51, goblin.getFootprintSize(), "size 1 -> (int) (128 * 0.4f)");
        assertFalse(goblin.hasConditionalActions());
    }

    @Test
    void resolvesConditionalOpsAgainstVars() {
        NPCComposition mortimer = decode(16175, MORTIMER);
        assertEquals("Mortimer", mortimer.getName());
        assertTrue(mortimer.hasConditionalActions());
        assertEquals(List.of("Talk-to", "", "Assignment", "Trade", "Rewards"), mortimer.getOps());
        // varbit 15785 == 0 is within each variant's [0, 0]: their (empty) text wins
        assertEquals(List.of("Talk-to", "", "", "", ""), mortimer.getOps(varp -> 0, varbit -> 0));
        assertEquals(List.of("Talk-to", "", "Assignment", "Trade", "Rewards"),
                mortimer.getOps(varp -> 0, varbit -> varbit == 15785 ? 1 : 0));
    }

    @Test
    void hiddenOpsStayUnset() {
        NPCComposition spot = decode(394, FISHING_SPOT);
        assertEquals("Rod Fishing spot", spot.getName());
        assertEquals(List.of("Lure", "", "Bait", "", ""), spot.getOps());
    }

    @Test
    void decodesMultiNpc() {
        NPCComposition multi = decode(10626, MULTI);
        assertEquals("", multi.getName());
        assertEquals(3, multi.getSize());
        assertEquals(922, multi.getCategory());
        assertEquals(0, multi.getCombatLevel());
        assertEquals(11875, multi.getVarbitId());
        assertEquals(-1, multi.getVarpIndex());
        assertArrayEquals(new int[]{10621, 10622}, multi.getConfigs());
        assertEquals(153, multi.getFootprintSize(), "size 3 -> (int) (384 * 0.4f)");
    }

    @Test
    void decodesHeadIconsAndStats() {
        NPCComposition mother = decode(8428, HEAD_ICON);
        assertEquals("Mother", mother.getName());
        assertArrayEquals(new int[]{440}, mother.getHeadIconArchiveIds());
        assertArrayEquals(new int[]{0}, mother.getHeadIconSpriteIndexes());
        assertArrayEquals(new int[]{150, 170, 150, 235, 105, 60}, mother.getStats());
        assertEquals(0, mother.getRenderPriority(), "opcode 99");
        assertEquals(Map.of(14, 4, 4, 50, 8, 200), mother.getParams());
    }

    @Test
    void absentFileGivesDefaultsLikeTheClient() {
        NPCComposition none = NpcDecoder.load(70000, id -> null, null);
        assertEquals(70000, none.getId());
        assertEquals("", none.getName());
        assertEquals(1, none.getSize());
        assertEquals(-1, none.getCategory());
        assertEquals(-1, none.getCombatLevel());
        assertEquals(List.of("", "", "", "", ""), none.getOps());
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, none.getStats());
        assertEquals(51, none.getFootprintSize());
        assertTrue(none.getParams().isEmpty());
    }

    @Test
    void loadReadsTheRequestedFile() {
        NPCComposition goblin = NpcDecoder.load(3029, id -> id == 3029 ? HexFormat.of().parseHex(GOBLIN) : null, null);
        assertEquals("Goblin", goblin.getName());
    }

    @Test
    void unknownOpcodeFails() {
        // Opcode 1 (the old 16-bit model list) is gone: the client would read nothing for it and desync
        assertThrows(ConfigFormatException.class, () -> decode(1, "0101000100"));
    }

    @Test
    void truncatedFileFails() {
        assertThrows(ConfigFormatException.class, () -> decode(1, "0c"));
    }
}
