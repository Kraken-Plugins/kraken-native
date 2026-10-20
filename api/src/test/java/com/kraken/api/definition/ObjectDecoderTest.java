package com.kraken.api.definition;

import com.kraken.api.internal.ConfigFormatException;
import com.kraken.api.internal.ObjectDecoder;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes real object (loc) type files (241.3's config group 6, byte-for-byte as the client holds them in memory) and
 * checks the fields the client's decoder and post-steps produce.
 */
class ObjectDecoderTest {

    /**
     * Tree: 2x2, one op plus a "hidden" one, a model list without shapes (opcode 7).
     */
    private static final String TREE = "0e020f024400001e43686f7020646f776e002068696464656e003d00bd1d0f2714515028010d8e"
            + "162107010000062202547265650000";

    /**
     * A multi-loc (opcode 92): varbit 11250 picks 54729, anything else 54730; active set by opcode 19.
     */
    private static final String MULTI = "13015c2bf2ffffd5ca00d5c900";

    /**
     * Hollow (opcode 74) after interact type 1 (opcode 27): ends up blocking neither movement nor projectiles.
     */
    private static final String HOLLOW = "13001b4a1206010000290d0900";

    /**
     * "Nothing": shaped models whose first shape is a wall (0), only a hidden op, so the client derives inactive.
     */
    private static final String WALL = "404400161e68696464656e00120607000008a6000000b69d0100001309020000b69e0300003f6f"
            + "04000008a70900003f6e16024e6f7468696e670000";

    /**
     * A ground decoration (shape 22) with a height offset (opcode 96).
     */
    private static final String DECOR = "600306010000412b1600";

    /**
     * Bush: op 1 "Exit" has an empty variant while varbit 15464 is within [0, 176] (opcode 101).
     */
    private static final String BUSH = "1d1427141e45786974006500ffff3c6800000000000000b00007010000ee5302427573680000";

    /**
     * Decodes an object file with no client, so no vars resolve.
     *
     * @param id  the object id
     * @param hex the file's bytes, in hex
     * @return the decoded object
     */
    private static ObjectComposition decode(int id, String hex) {
        return ObjectDecoder.decode(id, HexFormat.of().parseHex(hex), false, null);
    }

    @Test
    void decodesTree() {
        ObjectComposition tree = decode(1276, TREE);
        assertEquals(1276, tree.getId());
        assertEquals("Tree", tree.getName());
        assertEquals(2, tree.getSizeX());
        assertEquals(2, tree.getSizeY());
        assertEquals(List.of("Chop down", "", "", "", ""), tree.getOps());
        assertTrue(tree.blocksWalk());
        assertTrue(tree.blocksRange());
        assertTrue(tree.isActive(), "has models and no shapes");
        assertEquals(16, tree.getWallWidth());
        assertEquals(189, tree.getCategory());
        assertEquals(15, tree.getAmbient());
        assertEquals(100, tree.getContrast(), "20, times 5");
        assertEquals(0x5000, tree.getContouredGround(), "opcode 81: 0x50 << 8");
        assertArrayEquals(new int[]{1570}, tree.getModels());
        assertEquals(0, tree.getShapes().length);
        assertEquals(1, tree.getSupportItems(), "derived from blocking movement");
    }

    @Test
    void decodesMultiLoc() {
        ObjectComposition multi = decode(54733, MULTI);
        assertEquals("", multi.getName());
        assertTrue(multi.isActive());
        assertEquals(11250, multi.getVarbitId());
        assertEquals(-1, multi.getVarPlayerId());
        assertArrayEquals(new int[]{54729, 54730}, multi.getImpostorIds());
    }

    @Test
    void hollowClearsBlockingAfterThePostStep() {
        ObjectComposition hollow = decode(10062, HOLLOW);
        assertTrue(hollow.isHollow());
        assertFalse(hollow.blocksWalk());
        assertFalse(hollow.blocksRange());
        assertEquals(0, hollow.getInteractType());
        assertFalse(hollow.isActive(), "opcode 19 set 0");
        // supportItems is derived before the hollow fix-up, from interact type 1
        assertEquals(1, hollow.getSupportItems());
        assertArrayEquals(new int[]{10509}, hollow.getModels());
        assertArrayEquals(new int[]{9}, hollow.getShapes());
    }

    @Test
    void wallWithOnlyHiddenOpsIsInactive() {
        ObjectComposition wall = decode(13141, WALL);
        assertEquals("Nothing", wall.getName());
        assertEquals(List.of("", "", "", "", ""), wall.getOps());
        assertFalse(wall.isActive(), "first shape is 0, not 10, and no op is set");
        assertFalse(wall.hasShadow());
        assertEquals(22, wall.getMapSceneId());
        assertFalse(wall.blocksRange());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 9, 22}, wall.getShapes());
    }

    @Test
    void readsDecorHeightOffset() {
        ObjectComposition decor = decode(16710, DECOR);
        assertEquals(3, decor.getDecorHeightOffset());
        assertArrayEquals(new int[]{22}, decor.getShapes());
        assertFalse(decor.isActive());
    }

    @Test
    void resolvesConditionalOpsAgainstVars() {
        ObjectComposition bush = decode(33431, BUSH);
        assertEquals("Bush", bush.getName());
        assertTrue(bush.hasConditionalActions());
        assertEquals(List.of("Exit", "", "", "", ""), bush.getOps());
        assertEquals(List.of("", "", "", "", ""), bush.getOps(varp -> 0, varbit -> 100));
        assertEquals(List.of("Exit", "", "", "", ""), bush.getOps(varp -> 0, varbit -> 177));
        assertEquals(List.of("Exit", "", "", "", ""), bush.getOps(varp -> 0, varbit -> -1));
    }

    @Test
    void absentFileGivesDefaultsLikeTheClient() {
        ObjectComposition none = ObjectDecoder.load(99999, id -> null, null);
        assertEquals(99999, none.getId());
        assertEquals("", none.getName());
        assertEquals(1, none.getSizeX());
        assertEquals(1, none.getSizeY());
        assertTrue(none.blocksWalk());
        assertTrue(none.blocksRange());
        assertFalse(none.isActive());
        assertEquals(16, none.getWallWidth());
        assertEquals(1, none.getSupportItems());
        assertEquals(List.of("", "", "", "", ""), none.getOps());
        assertTrue(none.getParams().isEmpty());
    }

    @Test
    void secondModelListIsSkippedUnlessLowDetail() {
        // opcode 7 with model 1, then opcode 7 with model 2
        byte[] data = HexFormat.of().parseHex("07010000000107010000000200");
        assertArrayEquals(new int[]{1}, ObjectDecoder.decode(1, data, false, null).getModels());
        assertArrayEquals(new int[]{2}, ObjectDecoder.decode(1, data, true, null).getModels());
    }

    @Test
    void unknownOpcodeFails() {
        // Opcode 1 (the old 16-bit model list) is gone: the client would read nothing for it and desync
        assertThrows(ConfigFormatException.class, () -> decode(1, "01010001000a00"));
    }
}
