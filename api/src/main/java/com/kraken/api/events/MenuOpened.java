package com.kraken.api.events;

import com.kraken.api.MenuEntry;
import lombok.Value;

/**
 * The menu opened. Posted on the first poll that sees it open, with the entries it shows, read then.
 */
@Value
public class MenuOpened {

    /**
     * The open menu's entries, in {@code Client.getMenuEntries()} order: the last is the top row.
     */
    MenuEntry[] menuEntries;

    /**
     * The entry at the top of the menu.
     *
     * @return the top row, or null for an empty menu
     */
    public MenuEntry getFirstEntry() {
        return menuEntries.length == 0 ? null : menuEntries[menuEntries.length - 1];
    }
}
