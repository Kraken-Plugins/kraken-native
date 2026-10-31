package com.kraken.api.action;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each chatbox input mode maps to at most one dialog, and the modes that send no resume packet map to none.
 */
class InputDialogTest {

    @Test
    void plainDialogsOpenTheirFirstMode() {
        assertEquals(InputDialog.COUNT, InputDialog.of(7));
        assertEquals(InputDialog.NAME, InputDialog.of(8));
        assertEquals(InputDialog.STRING, InputDialog.of(9));
        assertEquals(InputDialog.OBJ, InputDialog.of(14));
        assertEquals(7, InputDialog.COUNT.getModes()[0]);
    }

    @Test
    void variantsBelongToTheirDialog() {
        assertEquals(InputDialog.COUNT, InputDialog.of(19));
        assertEquals(InputDialog.STRING, InputDialog.of(29));
        assertEquals(InputDialog.OBJ, InputDialog.of(17));
        assertTrue(InputDialog.STRING.hasMode(20));
        assertFalse(InputDialog.STRING.hasMode(7));
    }

    @Test
    void modesWithoutAResumePacketHaveNoDialog() {
        for (int mode : new int[]{0, 1, 6, 11, 26, -1}) {
            assertNull(InputDialog.of(mode), "mode " + mode);
        }
    }

    @Test
    void noModeIsShared() {
        Set<Integer> seen = new HashSet<>();
        for (InputDialog dialog : InputDialog.values()) {
            for (int mode : dialog.getModes()) {
                assertTrue(seen.add(mode), "mode " + mode + " is in two dialogs");
            }
        }
    }

    @Test
    void modesAreACopy() {
        InputDialog.NAME.getModes()[0] = 99;
        assertArrayEquals(new int[]{8}, InputDialog.NAME.getModes());
    }
}
