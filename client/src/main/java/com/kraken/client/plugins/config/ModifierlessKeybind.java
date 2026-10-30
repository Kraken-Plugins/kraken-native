package com.kraken.client.plugins.config;

import java.awt.event.KeyEvent;

/**
 * A {@link Keybind} that matches its key whatever modifiers are held, for settings that remap a key the player may
 * press with Shift or Ctrl down. Stored and shown as a keybind; the settings page captures it without modifiers.
 */
public class ModifierlessKeybind extends Keybind {

    /**
     * A keybind matched by its key alone.
     *
     * @param keyCode   a {@link KeyEvent} key code
     * @param modifiers the modifiers, kept for a modifier key on its own and otherwise ignored
     */
    public ModifierlessKeybind(int keyCode, int modifiers) {
        super(keyCode, modifiers);
    }

    /**
     * Whether a key press is this key, whatever else is held.
     *
     * @param e a key pressed event
     * @return true if the key code matches, or the key is a modifier this keybind is
     */
    public boolean matches(KeyEvent e) {
        if (getKeyCode() != KeyEvent.VK_UNDEFINED) {
            return e.getKeyCode() == getKeyCode();
        }
        Integer modifier = modifierFor(e.getKeyCode());
        return modifier != null && (getModifiers() & modifier) != 0;
    }
}
