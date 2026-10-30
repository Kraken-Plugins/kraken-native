package com.kraken.client.input;

import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.plugins.config.ModifierlessKeybind;

import java.awt.event.KeyEvent;

/**
 * One rule of a {@link KeyRemapMode}: a key going down becomes another key, or is dropped, and may move the hook to
 * another mode. The game sees the key it became, and the character the original would have typed is dropped with it.
 * Key codes are {@link KeyEvent}'s.
 *
 * @param keyCode         the key the rule waits for; {@link KeyEvent#VK_UNDEFINED} with modifiers for a modifier
 *                        key on its own
 * @param modifiers       the {@link java.awt.event.InputEvent} modifiers that must be held with it
 * @param ignoreModifiers true to match the key whatever modifiers are held
 * @param toKeyCode       the key it becomes, the same key to pass it through, or {@link #BLOCK}
 * @param nextMode        the mode to move to as the key goes down, or {@link #STAY}
 */
public record KeyRemap(int keyCode, int modifiers, boolean ignoreModifiers, int toKeyCode, int nextMode) {

    /**
     * A target key that drops the key.
     */
    public static final int BLOCK = -1;

    /**
     * A next mode that keeps the mode.
     */
    public static final int STAY = -1;

    /**
     * A rule turning a keybind into a key. A {@link ModifierlessKeybind} matches whatever modifiers are held.
     *
     * @param from      the keybind to remap
     * @param toKeyCode the key it becomes, or {@link #BLOCK}
     * @return the rule, staying in the mode
     */
    public static KeyRemap of(Keybind from, int toKeyCode) {
        return of(from, toKeyCode, STAY);
    }

    /**
     * A rule turning a keybind into a key and moving to a mode.
     *
     * @param from      the keybind to remap
     * @param toKeyCode the key it becomes, the same key to pass it through, or {@link #BLOCK}
     * @param nextMode  the mode to move to, or {@link #STAY}
     * @return the rule
     */
    public static KeyRemap of(Keybind from, int toKeyCode, int nextMode) {
        return new KeyRemap(from.getKeyCode(), from.getModifiers(),
                from instanceof ModifierlessKeybind || from.getKeyCode() == KeyEvent.VK_UNDEFINED, toKeyCode,
                nextMode);
    }

    /**
     * A rule passing a key through while moving to a mode, such as Enter starting typing.
     *
     * @param keyCode  the key
     * @param nextMode the mode to move to
     * @return the rule, matching the key whatever modifiers are held
     */
    public static KeyRemap transition(int keyCode, int nextMode) {
        return new KeyRemap(keyCode, 0, true, keyCode, nextMode);
    }

    /**
     * A rule dropping a key and moving to a mode, such as Escape ending typing.
     *
     * @param keyCode  the key
     * @param nextMode the mode to move to, or {@link #STAY}
     * @return the rule, matching the key whatever modifiers are held
     */
    public static KeyRemap block(int keyCode, int nextMode) {
        return new KeyRemap(keyCode, 0, true, BLOCK, nextMode);
    }
}
