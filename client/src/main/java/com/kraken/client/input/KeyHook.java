package com.kraken.client.input;

import java.util.List;

/**
 * Sends the key remapping to the native hook, which rewrites the game window's key messages by it: a table of
 * modes, each a list of rules, the mode in force, and whether to report the keys it handles. Internal: the hook
 * server hands one to {@link KeyManager#setKeyHook} while a hook with the window procedure hooked is connected.
 * Keys here are Windows virtual-key codes ({@link KeyManager#toWindows}).
 */
public interface KeyHook {

    /**
     * A rule's key that drops the message.
     */
    int BLOCK = 0xffff;

    /**
     * A rule's next mode that keeps the mode.
     */
    int STAY = 0xff;

    /**
     * The most modes a table holds.
     */
    int MAX_MODES = 8;

    /**
     * The modifier bits a rule needs, as the hook reads them.
     */
    int SHIFT = 1;
    int CTRL = 2;
    int ALT = 4;

    /**
     * One rule of a mode: a key going down with these modifiers becomes another key, or is dropped, and may move
     * the hook to another mode.
     *
     * @param vk           the virtual-key code the rule waits for
     * @param modifiers    the modifier bits that must be held, among those in the mask
     * @param modifierMask which modifier bits count; 0 matches whatever is held
     * @param toVk         the virtual-key code the key becomes, itself to pass it through, or {@link #BLOCK}
     * @param nextMode     the mode to move to as the key goes down, or {@link #STAY}
     */
    record Rule(int vk, int modifiers, int modifierMask, int toVk, int nextMode) {
    }

    /**
     * One mode of the table.
     *
     * @param blockTyping whether printable characters typed in this mode are dropped
     * @param rules       its rules, the first that matches a key winning
     */
    record Mode(boolean blockTyping, List<Rule> rules) {
    }

    /**
     * Replaces the table; an empty table passes every key through.
     *
     * @param modes the modes, at most {@link #MAX_MODES}
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void table(List<Mode> modes);

    /**
     * Sets the mode in force.
     *
     * @param mode the mode's index in the table
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void mode(int mode);

    /**
     * Turns reports of the keys the hook handles on or off.
     *
     * @param on whether to report
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void report(boolean on);
}
