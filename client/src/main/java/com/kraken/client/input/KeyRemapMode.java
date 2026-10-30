package com.kraken.client.input;

import java.util.List;

/**
 * One mode of a key remapping table ({@link KeyManager#setRemapTable}): what the hook does to keys while the mode is
 * in force. The first rule matching a key wins; a key no rule matches passes through.
 *
 * @param blockTyping whether printable characters typed in this mode are dropped, so nothing reaches the chat
 * @param remaps      the rules
 */
public record KeyRemapMode(boolean blockTyping, List<KeyRemap> remaps) {

    /**
     * Builds a mode.
     *
     * @param blockTyping whether typed characters are dropped
     * @param remaps      the rules
     */
    public KeyRemapMode {
        remaps = remaps == null ? List.of() : List.copyOf(remaps);
    }

    /**
     * A mode that passes every key through.
     */
    public static final KeyRemapMode PASS = new KeyRemapMode(false, List.of());
}
