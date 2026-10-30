package com.kraken.api.internal.menu;

import java.util.List;

/**
 * Sends menu rules to the native hook, which keeps them and applies them each time the client rebuilds the menu.
 * Internal: the client's hook server hands one to {@code GameSession.setMenuHook} while a hook with the minimenu
 * hooks is connected; {@code MenuEdits} sends its rules through it.
 */
public interface MenuHook {

    /**
     * Replaces every rule the hook holds.
     *
     * @param rules the rules, in the order to apply them
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void replace(List<WireRule> rules);

    /**
     * Adds rules, replacing any the hook holds with the same ids.
     *
     * @param rules the rules
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void upsert(List<WireRule> rules);

    /**
     * Removes rules by id.
     *
     * @param ids the ids
     * @throws java.io.UncheckedIOException if the connection to the hook broke
     */
    void remove(int[] ids);
}
