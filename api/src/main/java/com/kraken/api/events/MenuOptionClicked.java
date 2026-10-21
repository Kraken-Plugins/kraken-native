package com.kraken.api.events;

import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.widgets.Widget;
import lombok.Value;

/**
 * The client ran a menu entry: a left click on the game or an interface (which runs the menu's top entry), or a click
 * on a row of the open menu. A left click with nothing under the mouse runs "Cancel" or "Walk here".
 *
 * <p>The client copies every entry it runs and counts the cycles since; the API reads the copy when the count starts
 * over or the copy changes, and right away when the native hook pushes the op event the client raises for it
 * ({@code ON_NPC_OP}, {@code ON_LOC_OP}, {@code ON_TILE_OP}, {@code ON_PLAYER_OP}, {@code ON_COMPONENT_OP}). Two runs
 * between polls post once, for the later entry, and the same entry run twice within a cycle or two can post once.
 *
 * <p>Posted after the client ran the entry: the API only reads, so the click can't be consumed. Not provided:
 * {@code consume}, {@code isConsumed}, {@code isItemOp}, {@code getItemOp}, and the deprecated getters.
 */
@Value
public class MenuOptionClicked {

    /**
     * The entry the client ran, as it copied it.
     */
    MenuEntry menuEntry;

    /**
     * The first action parameter.
     *
     * @return param0
     */
    public int getParam0() {
        return menuEntry.getParam0();
    }

    /**
     * The second action parameter.
     *
     * @return param1
     */
    public int getParam1() {
        return menuEntry.getParam1();
    }

    /**
     * The option text.
     *
     * @return the option
     */
    public String getMenuOption() {
        return menuEntry.getOption();
    }

    /**
     * What the option acted on, with colour tags.
     *
     * @return the target
     */
    public String getMenuTarget() {
        return menuEntry.getTarget();
    }

    /**
     * The action the entry ran.
     *
     * @return the action
     */
    public MenuAction getMenuAction() {
        return menuEntry.getType();
    }

    /**
     * The entry's identifier.
     *
     * @return the identifier
     */
    public int getId() {
        return menuEntry.getIdentifier();
    }

    /**
     * The item the entry's component shows, read now.
     *
     * @return the item id, or -1
     */
    public int getItemId() {
        return menuEntry.getItemId();
    }

    /**
     * The component the entry acted on, read now.
     *
     * @return the widget, or null if the entry isn't on a component
     */
    public Widget getWidget() {
        return menuEntry.getWidget();
    }
}
