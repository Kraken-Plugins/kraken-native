package com.kraken.client.plugins.antidrag;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When Anti Drag's delay is in force, as Shift and Control go down and up.
 */
class AntiDragKeysTest {

    @Test
    void shiftHoldsTheDelayWhileItIsShiftOnly() {
        AntiDragKeys keys = new AntiDragKeys();
        assertFalse(keys.isOverriding(true));
        assertEquals(AntiDragKeys.Action.SET, keys.pressed(KeyEvent.VK_SHIFT, true, false));
        assertTrue(keys.isOverriding(true));
        assertEquals(AntiDragKeys.Action.RESET, keys.released(KeyEvent.VK_SHIFT, true, false));
        assertFalse(keys.isOverriding(true));
    }

    @Test
    void controlLiftsTheDelayOnlyWhenItIsNotShiftOnly() {
        AntiDragKeys keys = new AntiDragKeys();
        assertTrue(keys.isOverriding(false), "always on");
        assertEquals(AntiDragKeys.Action.NONE, keys.pressed(KeyEvent.VK_CONTROL, false, false),
                "Control does nothing unless it is set to");
        assertEquals(AntiDragKeys.Action.RESET, keys.pressed(KeyEvent.VK_CONTROL, false, true));
        assertFalse(keys.isOverriding(false));
        assertEquals(AntiDragKeys.Action.SET, keys.released(KeyEvent.VK_CONTROL, false, true));
        assertTrue(keys.isOverriding(false));

        assertEquals(AntiDragKeys.Action.NONE, keys.pressed(KeyEvent.VK_CONTROL, true, true),
                "with Shift-only, Control is ignored");
        assertEquals(AntiDragKeys.Action.NONE, keys.pressed(KeyEvent.VK_SHIFT, false, true),
                "and without it, Shift is");
    }

    @Test
    void changedSettingsAndALostKeyboardForgetTheKeys() {
        AntiDragKeys keys = new AntiDragKeys();
        keys.pressed(KeyEvent.VK_SHIFT, true, false);
        keys.settingsChanged(true, false);
        assertFalse(keys.isOverriding(true), "Shift-only again: Shift must go down anew");

        keys.pressed(KeyEvent.VK_CONTROL, false, true);
        keys.settingsChanged(false, false);
        assertTrue(keys.isOverriding(false), "Control no longer lifts the delay");

        keys.pressed(KeyEvent.VK_SHIFT, true, false);
        keys.clear();
        assertFalse(keys.isOverriding(true));
    }
}
