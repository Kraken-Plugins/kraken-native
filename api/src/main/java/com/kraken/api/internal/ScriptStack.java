package com.kraken.api.internal;

/**
 * The script engine's two stacks at one moment, as read from the game or as the native hook captured them for a
 * script event. Each array is as long as its stack's size, the oldest entry first and the top last.
 *
 * @param ints    the int stack
 * @param objects the object stack: {@code String}s, {@code Integer}s and {@code Long}s, null for kinds the API doesn't
 *                decode or entries the capture left out
 */
public record ScriptStack(int[] ints, Object[] objects) {

    /**
     * Both stacks empty.
     */
    public static final ScriptStack EMPTY = new ScriptStack(new int[0], new Object[0]);
}
