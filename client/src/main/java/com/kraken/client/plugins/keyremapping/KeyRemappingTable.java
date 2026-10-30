package com.kraken.client.plugins.keyremapping;

import com.kraken.client.input.KeyRemap;
import com.kraken.client.input.KeyRemapMode;
import com.kraken.client.plugins.config.Keybind;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The remapping table Key Remapping hands the native hook, and which of its modes is in force. While the chat is
 * locked, typed characters go nowhere and the camera and function keys stand in for the arrows and F-keys; Enter
 * (or a slash, which starts a message to a chat channel) unlocks the chat for typing, and Enter or Escape locks it
 * again. The hook moves between the modes by itself as those keys go down; the plugin picks the locked mode from
 * what the chat is showing.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class KeyRemappingTable {

    /**
     * Every key passes: the chat doesn't have the keyboard, or the plugin is off.
     */
    static final int OFF = 0;

    /**
     * The chat is locked: camera and F-key remaps, typing blocked.
     */
    static final int LOCKED = 1;

    /**
     * The chat is locked while a dialog takes number keys: camera remaps and Space, no F-keys.
     */
    static final int LOCKED_DIALOG = 2;

    /**
     * The chat is locked while an options dialog is open: camera remaps only.
     */
    static final int LOCKED_OPTIONS = 3;

    /**
     * The player is typing: Escape and Enter lock the chat again.
     */
    static final int TYPING = 4;

    /**
     * The keys that unlock the chat for typing: Enter, and the slash and semicolon that start a channel message.
     */
    private static final int[] TYPING_KEYS = {KeyEvent.VK_ENTER, KeyEvent.VK_SLASH, KeyEvent.VK_SEMICOLON};

    /**
     * Builds the table for the settings.
     *
     * @param config the settings
     * @return the modes, indexed by {@link #OFF} to {@link #TYPING}
     */
    static List<KeyRemapMode> build(KeyRemappingConfig config) {
        List<KeyRemap> locked = new ArrayList<>();
        List<KeyRemap> dialog = new ArrayList<>();
        List<KeyRemap> options = new ArrayList<>();
        if (config.cameraRemap()) {
            for (List<KeyRemap> mode : List.of(locked, dialog, options)) {
                remap(mode, config.up(), KeyEvent.VK_UP);
                remap(mode, config.down(), KeyEvent.VK_DOWN);
                remap(mode, config.left(), KeyEvent.VK_LEFT);
                remap(mode, config.right(), KeyEvent.VK_RIGHT);
            }
        }
        if (config.fkeyRemap()) {
            Keybind[] from = {config.f1(), config.f2(), config.f3(), config.f4(), config.f5(), config.f6(),
                    config.f7(), config.f8(), config.f9(), config.f10(), config.f11(), config.f12(), config.esc()};
            int[] to = {KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6,
                    KeyEvent.VK_F7, KeyEvent.VK_F8, KeyEvent.VK_F9, KeyEvent.VK_F10, KeyEvent.VK_F11, KeyEvent.VK_F12,
                    KeyEvent.VK_ESCAPE};
            for (int i = 0; i < from.length; i++) {
                remap(locked, from[i], to[i]);
            }
        }
        remap(dialog, config.space(), KeyEvent.VK_SPACE);
        remap(locked, config.control(), KeyEvent.VK_CONTROL);
        remap(dialog, config.control(), KeyEvent.VK_CONTROL);
        for (List<KeyRemap> mode : List.of(locked, dialog, options)) {
            for (int key : TYPING_KEYS) {
                mode.add(KeyRemap.transition(key, TYPING));
            }
        }
        List<KeyRemap> typing = List.of(KeyRemap.block(KeyEvent.VK_ESCAPE, LOCKED),
                KeyRemap.transition(KeyEvent.VK_ENTER, LOCKED));
        return List.of(KeyRemapMode.PASS, new KeyRemapMode(true, locked), new KeyRemapMode(true, dialog),
                new KeyRemapMode(true, options), new KeyRemapMode(false, typing));
    }

    /**
     * The mode in force for what the chat is doing.
     *
     * @param chatboxFocused whether the chatbox takes the keyboard
     * @param typing         whether the player unlocked the chat to type
     * @param dialogOpen     whether a dialog that takes number keys is open
     * @param optionsOpen    whether an options dialog is open
     * @return the mode
     */
    static int modeFor(boolean chatboxFocused, boolean typing, boolean dialogOpen, boolean optionsOpen) {
        if (!chatboxFocused) {
            return OFF;
        }
        if (typing) {
            return TYPING;
        }
        if (optionsOpen) {
            return LOCKED_OPTIONS;
        }
        return dialogOpen ? LOCKED_DIALOG : LOCKED;
    }

    /**
     * Adds a remap unless the keybind is unset or already the key.
     *
     * @param mode the mode's rules
     * @param from the keybind
     * @param to   the key it becomes
     */
    private static void remap(List<KeyRemap> mode, Keybind from, int to) {
        if (from == null || from.equals(Keybind.NOT_SET)) {
            return;
        }
        if (from.getKeyCode() == to || (from.getKeyCode() == KeyEvent.VK_UNDEFINED && to == KeyEvent.VK_CONTROL
                && from.equals(Keybind.CTRL))) {
            return;
        }
        mode.add(KeyRemap.of(from, to));
    }
}
