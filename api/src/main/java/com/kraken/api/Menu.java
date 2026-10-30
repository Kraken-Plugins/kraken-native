package com.kraken.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sub-menu under a row a plugin added ({@link MenuEntry#createSubMenu()}): the rows the client shows when the
 * mouse rests on the row. Its entries are the plugin's own, so clicking one runs its callback and nothing in the
 * client. The client's own sub-menus (a component op's sub-options) read as entries of the op instead
 * ({@link MenuEntry#getSubOp()}).
 *
 * <p>Not provided: the root menu ({@code Client.getMenu()}); the top rows are {@link Client#getMenuEntries()}.
 */
public final class Menu {

    private final MenuEntry parent;
    private final List<MenuEntry> entries = new ArrayList<>();

    /**
     * Creates the empty sub-menu of a row. Internal: {@link MenuEntry#createSubMenu()} makes it.
     *
     * @param parent the row
     */
    Menu(MenuEntry parent) {
        this.parent = parent;
    }

    /**
     * The sub-menu's entries, bottom row first, as {@link Client#getMenuEntries()} orders them.
     *
     * @return the entries
     */
    public synchronized MenuEntry[] getMenuEntries() {
        List<MenuEntry> bottomFirst = new ArrayList<>(entries);
        Collections.reverse(bottomFirst);
        return bottomFirst.toArray(new MenuEntry[0]);
    }

    /**
     * Replaces the sub-menu's entries.
     *
     * @param menuEntries the entries, bottom row first
     */
    public synchronized void setMenuEntries(MenuEntry[] menuEntries) {
        entries.clear();
        for (int i = menuEntries.length - 1; i >= 0; i--) {
            entries.add(menuEntries[i]);
        }
        parent.subMenuChanged();
    }

    /**
     * Adds a row to the sub-menu. The index counts from the bottom, as {@link #getMenuEntries()} does: 0 puts the row
     * at the bottom, -1 at the top; so rows created at 0 one after another read top to bottom in creation order.
     *
     * @param idx where to put the row, counting from the bottom, or -1 for the top
     * @return the new row, with its option, target and callback to set
     */
    public synchronized MenuEntry createMenuEntry(int idx) {
        MenuEntry entry = parent.createChild();
        int at = idx < 0 ? 0 : Math.max(0, entries.size() - idx);
        entries.add(Math.min(at, entries.size()), entry);
        parent.subMenuChanged();
        return entry;
    }

    /**
     * Takes a row out of the sub-menu.
     *
     * @param entry the row
     */
    public synchronized void removeMenuEntry(MenuEntry entry) {
        if (entries.remove(entry)) {
            parent.subMenuChanged();
        }
    }

    /**
     * The entries, top first. Internal: the rule is built from this order.
     *
     * @return a copy of the entries
     */
    synchronized List<MenuEntry> topFirst() {
        return new ArrayList<>(entries);
    }
}
