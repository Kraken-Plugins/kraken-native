package com.kraken.api.internal.event;

/**
 * What a component's listener ran a root script with, as the native hook captured it when the script started.
 * Internal: {@code ScriptEvent} reads it; plugins don't need it.
 *
 * @param sourceId    the packed id of the component the listener belongs to, or -1
 * @param sourceIndex that component's index under its static parent, or -1 for a static component
 * @param targetId    the packed id of the component dragged onto or targeted, or -1
 * @param targetIndex that component's index, or -1
 * @param op          the op that fired the listener, 1-based
 * @param subOp       the op's sub-option
 * @param mouseX      the mouse's x
 * @param mouseY      the mouse's y
 * @param keyCode     the key's code, for a key listener
 * @param keyChar     the key's character, for a key listener
 * @param opbase      the op's base name
 * @param arguments   the listener's arguments as it holds them: the script's id first, then {@code Integer}s,
 *                    {@code String}s and {@code Long}s (null for other kinds), with the event placeholders unreplaced
 */
public record ScriptRequest(int sourceId, int sourceIndex, int targetId, int targetIndex, int op, int subOp,
                            int mouseX, int mouseY, int keyCode, int keyChar, String opbase, Object[] arguments) {

    /**
     * The request of a root run no listener started (a trigger script): only the script's id, as its one argument.
     *
     * @param scriptId the script's id
     * @return the request
     */
    public static ScriptRequest of(int scriptId) {
        return new ScriptRequest(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, "", new Object[]{scriptId});
    }
}
