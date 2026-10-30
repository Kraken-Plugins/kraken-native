package com.kraken.client.plugins.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A key with modifiers, as a setting: a {@link ConfigItem} of this type gets a key-capture button on the settings page
 * and is stored as {@code keyCode:modifiers}. A modifier pressed on its own (Alt, Ctrl, Shift) is kept as the modifier
 * with no key, so {@link #ALT} is the same whichever Alt key was pressed. Key codes and modifiers are
 * {@link KeyEvent}'s and {@link InputEvent}'s.
 */
@Getter
@EqualsAndHashCode
public class Keybind {

    private static final Map<Integer, Integer> MODIFIER_KEYS = Map.of(
            InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL,
            InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT,
            InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT,
            InputEvent.META_DOWN_MASK, KeyEvent.VK_META);

    /**
     * Every modifier a keybind can have.
     */
    public static final int MODIFIER_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
            | InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    /**
     * No key: never pressed.
     */
    public static final Keybind NOT_SET = new Keybind(KeyEvent.VK_UNDEFINED, 0);

    /**
     * Either Ctrl key on its own.
     */
    public static final Keybind CTRL = new Keybind(KeyEvent.VK_UNDEFINED, InputEvent.CTRL_DOWN_MASK);

    /**
     * Either Alt key on its own.
     */
    public static final Keybind ALT = new Keybind(KeyEvent.VK_UNDEFINED, InputEvent.ALT_DOWN_MASK);

    /**
     * Either Shift key on its own.
     */
    public static final Keybind SHIFT = new Keybind(KeyEvent.VK_UNDEFINED, InputEvent.SHIFT_DOWN_MASK);

    /**
     * The key, or {@link KeyEvent#VK_UNDEFINED} for modifiers alone.
     */
    private final int keyCode;

    /**
     * The modifiers held with the key, a combination of {@link #MODIFIER_MASK}'s bits.
     */
    private final int modifiers;

    /**
     * A keybind. A modifier key given as the key becomes one of the modifiers.
     *
     * @param keyCode   a {@link KeyEvent} key code
     * @param modifiers {@link InputEvent} modifier masks ({@code ..._DOWN_MASK}); others are dropped
     */
    public Keybind(int keyCode, int modifiers) {
        int mods = modifiers & MODIFIER_MASK;
        Integer modifier = modifierFor(keyCode);
        if (modifier != null) {
            mods |= modifier;
            keyCode = KeyEvent.VK_UNDEFINED;
        }
        this.keyCode = keyCode;
        this.modifiers = mods;
    }

    /**
     * The keybind a key press makes.
     *
     * @param e a key pressed event
     * @return its key with the modifiers held
     */
    public static Keybind of(KeyEvent e) {
        return new Keybind(e.getKeyCode(), e.getModifiersEx());
    }

    /**
     * The modifier mask a modifier key sets.
     *
     * @param keyCode a key code
     * @return the mask, or null if the key isn't a modifier
     */
    public static Integer modifierFor(int keyCode) {
        for (Map.Entry<Integer, Integer> entry : MODIFIER_KEYS.entrySet()) {
            if (entry.getValue() == keyCode) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The keys that must all be held for this keybind: its modifiers' keys, then its key.
     *
     * @return {@link KeyEvent} key codes; empty for {@link #NOT_SET}
     */
    public List<Integer> getKeyCodes() {
        List<Integer> keys = new ArrayList<>();
        for (int mask : new int[]{InputEvent.CTRL_DOWN_MASK, InputEvent.ALT_DOWN_MASK, InputEvent.SHIFT_DOWN_MASK,
                InputEvent.META_DOWN_MASK}) {
            if ((modifiers & mask) != 0) {
                keys.add(MODIFIER_KEYS.get(mask));
            }
        }
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            keys.add(keyCode);
        }
        return keys;
    }

    /**
     * The keybind as the settings page shows it, such as {@code Ctrl+Shift+F} or {@code Alt}.
     *
     * @return the text, or {@code Not set}
     */
    @Override
    public String toString() {
        if (keyCode == KeyEvent.VK_UNDEFINED && modifiers == 0) {
            return "Not set";
        }
        StringBuilder text = new StringBuilder();
        if (modifiers != 0) {
            text.append(InputEvent.getModifiersExText(modifiers));
        }
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            if (!text.isEmpty()) {
                text.append('+');
            }
            text.append(KeyEvent.getKeyText(keyCode));
        }
        return text.toString();
    }
}
