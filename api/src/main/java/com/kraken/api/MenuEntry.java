package com.kraken.api;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.widgets.Widget;
import lombok.Getter;
import lombok.ToString;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One entry of the client's minimenu (the right-click menu): an option, what it targets and the action it runs. Get
 * them from {@link Client#getMenuEntries()}, from the menu events, or make one with {@link Client#createMenuEntry}.
 *
 * <p>An entry read from the client is a snapshot of one read of the client's entry: the text, ids and parameters
 * are as of that read. Two such entries are equal when the client's entries were: the same option, target, action,
 * identifier and sub-option, and the same world tile (for a tile, loc or ground item, whose parameters follow from
 * it and the map base) or else the same parameters.
 * {@link #getNpc()}, {@link #getPlayer()}, {@link #getActor()}, {@link #getWidget()} and {@link #getItemId()} look
 * their object up in the game when called.
 *
 * <p>The setters change the menu through the client's native hook ({@link MenuEdits}). On an entry read from the
 * client they become a rule on that row: {@link #setOption}, {@link #setTarget} and {@link #setDeprioritized} apply
 * from the client's next rebuild of the menu (the next client cycle, while the menu is closed) for as long as the
 * row keeps showing, and {@link #onClick} has the hook report the row's clicks. The action, identifier and parameters
 * of such an entry are the client's and can't be set. An entry made with {@link Client#createMenuEntry} is a row of
 * the plugin's own: every setter sets what it shows and reports, its click runs its callback and nothing in the
 * client, and it can have a sub-menu ({@link #createSubMenu()}). Two created entries are equal only when they are the
 * same object.
 *
 * <p>Not provided: {@code isForceLeftClick}, {@code getWorldViewId}, {@code isItemOp}, {@code getItemOp}. A row that
 * only groups entries (a selected item's targets) reads as the group's first entry, the one the client runs when
 * that row is left-clicked.
 */
@ToString(onlyExplicitlyIncluded = true)
public final class MenuEntry {

    private static final Set<MenuAction> NPC_ACTIONS = EnumSet.of(MenuAction.WIDGET_TARGET_ON_NPC,
            MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
            MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION, MenuAction.EXAMINE_NPC);
    private static final Set<MenuAction> PLAYER_ACTIONS = EnumSet.of(MenuAction.WIDGET_TARGET_ON_PLAYER,
            MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION, MenuAction.PLAYER_THIRD_OPTION,
            MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION,
            MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION);
    private static final Set<MenuAction> WIDGET_ACTIONS = EnumSet.of(MenuAction.CC_OP, MenuAction.CC_OP_LOW_PRIORITY,
            MenuAction.WIDGET_TARGET_ON_WIDGET, MenuAction.WIDGET_TYPE_1, MenuAction.WIDGET_TARGET,
            MenuAction.WIDGET_CLOSE, MenuAction.WIDGET_TYPE_4, MenuAction.WIDGET_TYPE_5, MenuAction.WIDGET_CONTINUE);

    private final Client client;

    /**
     * Whether this is a row of a plugin's own ({@link Client#createMenuEntry}) rather than one read from the client.
     */
    private final boolean created;

    /**
     * A created row's id, which the hook reports its clicks by; 0 for a row read from the client.
     */
    private final int rowId;

    /**
     * The option text, e.g. "Walk here", "Attack" or "Take".
     */
    @ToString.Include
    @Getter
    private volatile String option;

    /**
     * What the option acts on, with the client's colour tags (e.g. {@code <col=ffff00>Goblin<col=ff00>  (level-2)});
     * empty when it acts on nothing.
     */
    @ToString.Include
    @Getter
    private volatile String target;

    /**
     * An id for what the entry acts on: see the class description for what it is per action.
     */
    @ToString.Include
    @Getter
    private volatile int identifier;

    /**
     * The action the entry runs.
     */
    @ToString.Include
    @Getter
    private volatile MenuAction type;

    /**
     * The first action parameter: see the class description.
     */
    @ToString.Include
    @Getter
    private volatile int param0;

    /**
     * The second action parameter: see the class description.
     */
    @ToString.Include
    @Getter
    private volatile int param1;

    /**
     * The world tile of what the entry acts on, for a tile, loc or ground item; null otherwise. The native entry
     * keeps it, and the client's {@code ON_TILE_OP}, {@code ON_LOC_OP} and {@code ON_OBJ_OP} events hand it to Lua.
     */
    @ToString.Include
    @Getter
    private final WorldPoint worldLocation;

    /**
     * The sub-option of a component op's sub-menu row, 1-based; 0 for every other entry.
     */
    @ToString.Include
    @Getter
    private final int subOp;

    private volatile int itemId = -1;
    private volatile boolean deprioritized;
    private volatile Consumer<MenuEntry> onClick;
    private volatile Menu subMenu;
    private final MenuEntry parentRow;

    /**
     * Builds an entry from its fields as the client keeps them, after the reader has worked out the action.
     * Internal: the menu reader makes these; plugins get them from the client and the events.
     *
     * @param client        the client the lookups read through
     * @param option        the option text
     * @param target        the target text, with colour tags
     * @param identifier    the identifier
     * @param type          the action
     * @param param0        the first parameter
     * @param param1        the second parameter
     * @param worldLocation the subject's world tile, or null when it has none
     */
    public MenuEntry(Client client, String option, String target, int identifier, MenuAction type, int param0,
                     int param1, WorldPoint worldLocation) {
        this(client, option, target, identifier, type, param0, param1, worldLocation, 0);
    }

    /**
     * Builds a sub-option row's entry: a component op's entry with the sub-option the row runs. Internal: the menu
     * reader makes these.
     *
     * @param client        the client the lookups read through
     * @param option        the option text
     * @param target        the target text, with colour tags
     * @param identifier    the identifier
     * @param type          the action
     * @param param0        the first parameter
     * @param param1        the second parameter
     * @param worldLocation the subject's world tile, or null when it has none
     * @param subOp         the sub-option, 1-based, or 0 for none
     */
    public MenuEntry(Client client, String option, String target, int identifier, MenuAction type, int param0,
                     int param1, WorldPoint worldLocation, int subOp) {
        this.client = client;
        this.created = false;
        this.rowId = 0;
        this.parentRow = null;
        this.option = option;
        this.target = target;
        this.identifier = identifier;
        this.type = type;
        this.param0 = param0;
        this.param1 = param1;
        this.worldLocation = worldLocation;
        this.subOp = subOp;
    }

    /**
     * Builds a row of a plugin's own, empty, of action {@link MenuAction#RUNELITE}. Internal: {@link MenuEdits}
     * makes these for {@link Client#createMenuEntry} and for the rows its rules add.
     *
     * @param client    the client the lookups read through
     * @param rowId     the row's id
     * @param parentRow the row this one is a sub row of, or null for a top row
     */
    public MenuEntry(Client client, int rowId, MenuEntry parentRow) {
        this.client = client;
        this.created = true;
        this.rowId = rowId;
        this.parentRow = parentRow;
        this.option = "";
        this.target = "";
        this.identifier = 0;
        this.type = MenuAction.RUNELITE;
        this.param0 = 0;
        this.param1 = 0;
        this.worldLocation = null;
        this.subOp = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MenuEntry other) || created || other.created) {
            return false;
        }
        return identifier == other.identifier && type == other.type && subOp == other.subOp
                && option.equals(other.option) && target.equals(other.target)
                && Objects.equals(worldLocation, other.worldLocation)
                && (worldLocation != null || param0 == other.param0 && param1 == other.param1);
    }

    @Override
    public int hashCode() {
        if (created) {
            return System.identityHashCode(this);
        }
        return worldLocation != null
                ? Objects.hash(option, target, identifier, type, subOp, worldLocation)
                : Objects.hash(option, target, identifier, type, subOp, param0, param1);
    }

    /**
     * Sets the option text. On a row read from the client it is written from the next menu rebuild on, for as long
     * as the row shows.
     *
     * @param option the text
     * @return this entry
     */
    public MenuEntry setOption(String option) {
        Objects.requireNonNull(option, "option");
        if (created) {
            this.option = option;
            edits().entryChanged(this);
        } else {
            edits().setOption(this, option);
        }
        return this;
    }

    /**
     * Sets the target text, colour tags included. On a row read from the client it is written from the next menu
     * rebuild on, for as long as the row shows.
     *
     * @param target the text
     * @return this entry
     */
    public MenuEntry setTarget(String target) {
        Objects.requireNonNull(target, "target");
        if (created) {
            this.target = target;
            edits().entryChanged(this);
        } else {
            edits().setTarget(this, target);
        }
        return this;
    }

    /**
     * Sets the identifier a created row reports.
     *
     * @param identifier the identifier
     * @return this entry
     * @throws IllegalStateException on a row read from the client, whose identifier is the client's
     */
    public MenuEntry setIdentifier(int identifier) {
        requireCreated("identifier");
        this.identifier = identifier;
        return this;
    }

    /**
     * Sets the action a created row reports.
     *
     * @param type the action
     * @return this entry
     * @throws IllegalStateException on a row read from the client, whose action is the client's
     */
    public MenuEntry setType(MenuAction type) {
        requireCreated("type");
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * Sets the first parameter a created row reports.
     *
     * @param param0 the parameter
     * @return this entry
     * @throws IllegalStateException on a row read from the client, whose parameters are the client's
     */
    public MenuEntry setParam0(int param0) {
        requireCreated("param0");
        this.param0 = param0;
        return this;
    }

    /**
     * Sets the second parameter a created row reports.
     *
     * @param param1 the parameter
     * @return this entry
     * @throws IllegalStateException on a row read from the client, whose parameters are the client's
     */
    public MenuEntry setParam1(int param1) {
        requireCreated("param1");
        this.param1 = param1;
        return this;
    }

    /**
     * Sets the item id a created row reports from {@link #getItemId()}.
     *
     * @param itemId the item id, -1 for none
     * @return this entry
     * @throws IllegalStateException on a row read from the client, whose item is its component's
     */
    public MenuEntry setItemId(int itemId) {
        requireCreated("itemId");
        this.itemId = itemId;
        return this;
    }

    /**
     * Moves a row read from the client to the bottom of the menu, above Cancel, so the row above it becomes the left
     * click, from the next menu rebuild on and for as long as the row shows; or puts it back. On a created row it is
     * only remembered.
     *
     * @param deprioritized true to move the row down
     * @return this entry
     */
    public MenuEntry setDeprioritized(boolean deprioritized) {
        if (created) {
            this.deprioritized = deprioritized;
        } else {
            edits().setDeprioritized(this, deprioritized);
        }
        return this;
    }

    /**
     * Whether the row is deprioritized: moved to the bottom by {@link #setDeprioritized}.
     *
     * @return true if it is
     */
    public boolean isDeprioritized() {
        return created ? deprioritized : edits().isDeprioritized(this);
    }

    /**
     * Sets what to do when the row is clicked, run on the client thread with this entry. A created row runs it and
     * nothing else; on a row read from the client the hook reports the click and the client runs the row as usual.
     *
     * @param callback the callback, or null for none
     * @return this entry
     */
    public MenuEntry onClick(Consumer<MenuEntry> callback) {
        this.onClick = callback;
        if (created) {
            edits().entryChanged(this);
        } else {
            edits().report(this, callback);
        }
        return this;
    }

    /**
     * The click callback.
     *
     * @return the callback set with {@link #onClick(Consumer)}, or null
     */
    public Consumer<MenuEntry> onClick() {
        return onClick;
    }

    /**
     * The sub-menu under a created row.
     *
     * @return the sub-menu, or null if none was created
     */
    public Menu getSubMenu() {
        return subMenu;
    }

    /**
     * Gives a created top row a sub-menu, or returns the one it has. The client shows it when the mouse rests on the
     * row.
     *
     * @return the sub-menu
     * @throws IllegalStateException on a row read from the client, or on a sub row
     */
    public Menu createSubMenu() {
        requireCreated("sub-menu");
        if (parentRow != null) {
            throw new IllegalStateException("a sub row can't have a sub-menu of its own");
        }
        Menu menu = subMenu;
        if (menu == null) {
            menu = new Menu(this);
            subMenu = menu;
            edits().entryChanged(this);
        }
        return menu;
    }

    /**
     * The NPC the entry acts on, looked up now by its index.
     *
     * @return the NPC's handle, or null if the entry isn't an NPC action or no NPC is at that index any more
     */
    public NPC getNpc() {
        return NPC_ACTIONS.contains(type) ? client.npc(identifier) : null;
    }

    /**
     * The player the entry acts on, looked up now by its index.
     *
     * @return the player's handle, or null if the entry isn't a player action or no player is at that index any more
     */
    public Player getPlayer() {
        return PLAYER_ACTIONS.contains(type) ? client.player(identifier) : null;
    }

    /**
     * The NPC or player the entry acts on.
     *
     * @return {@link #getNpc()} or {@link #getPlayer()}, whichever applies; null for an entry on neither
     */
    public Actor getActor() {
        NPC npc = getNpc();
        return npc != null ? npc : getPlayer();
    }

    /**
     * The component the entry acts on, read now: the component at param1, or its dynamic child param0.
     *
     * @return the widget, or null if the entry isn't a component action or the client holds no such component
     */
    public Widget getWidget() {
        if (!WIDGET_ACTIONS.contains(type)) {
            return null;
        }
        Widget widget = client.getWidget(param1);
        return widget == null || param0 < 0 ? widget : widget.getChild(param0);
    }

    /**
     * The item the entry's component shows, such as the item in an inventory slot, read now; a created row's is the
     * one set with {@link #setItemId}.
     *
     * @return the item id, or -1 if the entry isn't on a component or the component shows no item
     */
    public int getItemId() {
        if (created) {
            return itemId;
        }
        Widget widget = getWidget();
        return widget == null ? -1 : widget.getItemId();
    }

    /**
     * Whether this is a row of a plugin's own rather than one read from the client. Internal: the menu edits and
     * the reader tell them apart with it.
     *
     * @return true for a row made with {@link Client#createMenuEntry} or added by a rule
     */
    public boolean isCreated() {
        return created;
    }

    /**
     * A created row's id. Internal: the hook reports clicks by it.
     *
     * @return the row id, or 0 for a row read from the client
     */
    public int getRowId() {
        return rowId;
    }

    /**
     * The created top row a sub row hangs under. Internal.
     *
     * @return the parent, or null for a top row or a row read from the client
     */
    public MenuEntry getParentRow() {
        return parentRow;
    }

    /**
     * Makes a sub row of this created row, for its sub-menu.
     *
     * @return the sub row
     */
    MenuEntry createChild() {
        return edits().createChild(this);
    }

    /**
     * Notes that the sub-menu changed, so the row's rule is sent again.
     */
    void subMenuChanged() {
        edits().entryChanged(this);
    }

    private MenuEdits edits() {
        return client.getMenuEdits();
    }

    private void requireCreated(String what) {
        if (!created) {
            throw new IllegalStateException("the " + what + " of a row read from the client is the client's own");
        }
    }
}
