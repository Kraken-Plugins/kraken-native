package com.kraken.api;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.widgets.Widget;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.ToString;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One entry of the client's minimenu (the right-click menu): an option, what it targets and the action it runs. Get
 * them from {@link Client#getMenuEntries()}, or from the menu events.
 *
 * <p>A snapshot of one read of the client's entry: the text, ids and parameters are as of that read. Two entries are
 * equal when the client's entries were: the same option, target, action and identifier, and the same world tile (for
 * a tile, loc or ground item, whose parameters follow from it and the map base) or else the same parameters.
 * {@link #getNpc()}, {@link #getPlayer()}, {@link #getActor()}, {@link #getWidget()} and {@link #getItemId()} look
 * their object up in the game when called.
 *
 * <p>The native entry keeps what it is about (subject kind, type id, index, world tile) and which op it runs; the
 * identifier and parameters below are worked out from those, the way the action ids number them:
 * <ul>
 *   <li>NPC and player actions: the identifier is the NPC or player index; both parameters are 0.
 *   <li>Loc and ground item actions: the identifier is the loc or item id; the parameters are the subject's scene x
 *       and y (its world tile less the map base).
 *   <li>{@link MenuAction#WALK}: the parameters are the scene x and y of the tile walked to. The native entry holds
 *       the tile, not the mouse position, so these are scene coordinates.
 *   <li>Component actions: param1 is the component's packed id ({@code group << 16 | child}), param0 its dynamic child
 *       index (-1 for none); for {@link MenuAction#CC_OP} and {@link MenuAction#CC_OP_LOW_PRIORITY} the identifier is
 *       the op, 1-10.
 *   <li>{@link MenuAction#CANCEL} and anything {@link MenuAction#UNKNOWN}: identifier and parameters are 0.
 * </ul>
 *
 * <p>Not provided: the setters, {@code onClick}, {@code isForceLeftClick}, {@code getWorldViewId},
 * {@code isDeprioritized}, {@code isItemOp}, {@code getItemOp} and the submenu methods. A row that opens a submenu
 * reads as the submenu's first entry, the one the client runs when that row is left-clicked.
 */
@Getter
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

    @Getter(AccessLevel.NONE)
    private final Client client;

    /**
     * The option text, e.g. "Walk here", "Attack" or "Take".
     */
    @ToString.Include
    private final String option;

    /**
     * What the option acts on, with the client's colour tags (e.g. {@code <col=ffff00>Goblin<col=ff00>  (level-2)});
     * empty when it acts on nothing.
     */
    @ToString.Include
    private final String target;

    /**
     * An id for what the entry acts on: see the class description for what it is per action.
     */
    @ToString.Include
    private final int identifier;

    /**
     * The action the entry runs.
     */
    @ToString.Include
    private final MenuAction type;

    /**
     * The first action parameter: see the class description.
     */
    @ToString.Include
    private final int param0;

    /**
     * The second action parameter: see the class description.
     */
    @ToString.Include
    private final int param1;

    /**
     * The world tile of what the entry acts on, for a tile, loc or ground item; null otherwise. The native entry
     * keeps it, and the client's {@code ON_TILE_OP}, {@code ON_LOC_OP} and {@code ON_OBJ_OP} events hand it to Lua.
     */
    @ToString.Include
    private final WorldPoint worldLocation;

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
        this.client = client;
        this.option = option;
        this.target = target;
        this.identifier = identifier;
        this.type = type;
        this.param0 = param0;
        this.param1 = param1;
        this.worldLocation = worldLocation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MenuEntry other)) {
            return false;
        }
        return identifier == other.identifier && type == other.type && option.equals(other.option)
                && target.equals(other.target) && Objects.equals(worldLocation, other.worldLocation)
                && (worldLocation != null || param0 == other.param0 && param1 == other.param1);
    }

    @Override
    public int hashCode() {
        return worldLocation != null
                ? Objects.hash(option, target, identifier, type, worldLocation)
                : Objects.hash(option, target, identifier, type, param0, param1);
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
     * The item the entry's component shows, such as the item in an inventory slot, read now.
     *
     * @return the item id, or -1 if the entry isn't on a component or the component shows no item
     */
    public int getItemId() {
        Widget widget = getWidget();
        return widget == null ? -1 : widget.getItemId();
    }
}
