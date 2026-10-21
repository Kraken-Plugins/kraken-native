package com.kraken.api.events;

import com.kraken.api.MenuEntry;
import lombok.Value;

/**
 * An entry appeared in the menu: the mouse moved onto something with options, or the menu changed under it. The API
 * compares the menu's rows on every poll and posts one of these for each entry that wasn't there before, after the
 * {@link MenuEntryRemoved}s of the entries that went. The client rebuilds its menu every cycle, but an entry that
 * stays the same posts nothing; while the menu is open it doesn't change. When tracking starts as the API attaches,
 * one is posted for every entry in the menu.
 */
@Value
public class MenuEntryAdded {

    /**
     * The entry, as read.
     */
    MenuEntry menuEntry;

    /**
     * The entry's option text, e.g. "Walk here" or "Attack".
     *
     * @return the option
     */
    public String getOption() {
        return menuEntry.getOption();
    }

    /**
     * What the option acts on, with colour tags; empty when it acts on nothing.
     *
     * @return the target
     */
    public String getTarget() {
        return menuEntry.getTarget();
    }

    /**
     * The action's id.
     *
     * @return the id of the entry's {@code MenuAction}
     */
    public int getType() {
        return menuEntry.getType().getId();
    }

    /**
     * An id for what the entry acts on.
     *
     * @return the identifier
     */
    public int getIdentifier() {
        return menuEntry.getIdentifier();
    }

    /**
     * The first action parameter.
     *
     * @return param0
     */
    public int getActionParam0() {
        return menuEntry.getParam0();
    }

    /**
     * The second action parameter.
     *
     * @return param1
     */
    public int getActionParam1() {
        return menuEntry.getParam1();
    }

    /**
     * The item the entry's component shows, read now.
     *
     * @return the item id, or -1
     */
    public int getItemId() {
        return menuEntry.getItemId();
    }
}
