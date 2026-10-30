package com.kraken.api.menu;

import com.kraken.api.MenuEntry;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A row a {@link MenuRule.Add} puts in the menu: its texts, what to do when it is clicked, and the rows of the
 * sub-menu under it. Clicking it runs nothing in the client; the callback gets a {@link MenuEntry} of action
 * {@link com.kraken.api.MenuAction#RUNELITE} with the row's texts.
 *
 * @param option   the option text
 * @param target   the target text, with colour tags; "" for none
 * @param onClick  what to do when the row is clicked, or null for nothing
 * @param children the sub-menu's rows, top first; empty for none. Their own children are ignored
 */
public record MenuRow(String option, String target, Consumer<MenuEntry> onClick, List<MenuRow> children) {

    /**
     * The most sub rows one row keeps.
     */
    public static final int MAX_CHILDREN = 32;

    /**
     * Builds a row.
     *
     * @param option   the option text
     * @param target   the target text, "" for none
     * @param onClick  what to do when the row is clicked, or null
     * @param children the sub-menu's rows, top first
     */
    public MenuRow {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(target, "target");
        children = children == null ? List.of() : List.copyOf(children);
        if (children.size() > MAX_CHILDREN) {
            throw new IllegalArgumentException("at most " + MAX_CHILDREN + " sub rows, got " + children.size());
        }
    }

    /**
     * A row without a sub-menu.
     *
     * @param option  the option text
     * @param target  the target text, "" for none
     * @param onClick what to do when the row is clicked, or null
     * @return the row
     */
    public static MenuRow of(String option, String target, Consumer<MenuEntry> onClick) {
        return new MenuRow(option, target, onClick, List.of());
    }
}
