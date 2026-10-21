package com.kraken.api.events;

import com.kraken.api.MenuEntry;
import lombok.Value;

/**
 * An entry left the menu: the mouse moved off what it was for, or the menu changed under it. The API compares the
 * menu's rows on every poll and posts one of these for each entry that went, before the {@link MenuEntryAdded}s of
 * the entries that came. An entry the client runs stays in the menu until the menu next changes, so clicking it posts
 * a {@link MenuOptionClicked}, not this.
 */
@Value
public class MenuEntryRemoved {

    /**
     * The entry, as it was last read.
     */
    MenuEntry menuEntry;

    /**
     * The entry's option text.
     *
     * @return the option
     */
    public String getOption() {
        return menuEntry.getOption();
    }

    /**
     * What the option acted on, with colour tags.
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
     * An id for what the entry acted on.
     *
     * @return the identifier
     */
    public int getIdentifier() {
        return menuEntry.getIdentifier();
    }
}
