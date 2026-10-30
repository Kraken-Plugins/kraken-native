package com.kraken.api;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * What a menu entry does when it runs: the action type of a {@link MenuEntry}. The ids are the menu opcodes the Java
 * client numbers its entries with.
 *
 * <p>The native client doesn't keep an opcode in its entries. It keeps what the entry is about (an NPC, a loc, a
 * ground item, a player, a tile or a component) and which of its ops it runs, and the API turns that pair into the
 * action here: op n of an NPC is {@code NPC_FIRST_OPTION} + n - 1, a loc's fifth op is
 * {@link #GAME_OBJECT_FIFTH_OPTION}, the Examine op is the subject's {@code EXAMINE_*}, and an entry aimed by a
 * selected item or spell ("Use Knife -> Goblin") is the subject's {@code WIDGET_TARGET_ON_*}.
 *
 * <p>Not provided: the deprecated {@code ITEM_USE*}, {@code ITEM_*_OPTION} and {@code WIDGET_*_OPTION} actions, the
 * world entity and {@code SET_HEADING} actions, the {@code RUNELITE_*} variants other than {@link #RUNELITE}, since
 * a row added through {@link Client#createMenuEntry} runs nothing in the client; and
 * {@code MENU_ACTION_DEPRIORITIZE_OFFSET}, since the native client keeps an entry's priority apart from its action.
 */
public enum MenuAction {

    /**
     * A selected item or spell used on a loc.
     */
    WIDGET_TARGET_ON_GAME_OBJECT(2),

    /**
     * A loc's first op.
     */
    GAME_OBJECT_FIRST_OPTION(3),

    /**
     * A loc's second op.
     */
    GAME_OBJECT_SECOND_OPTION(4),

    /**
     * A loc's third op.
     */
    GAME_OBJECT_THIRD_OPTION(5),

    /**
     * A loc's fourth op.
     */
    GAME_OBJECT_FOURTH_OPTION(6),

    /**
     * A loc's fifth op, which the client ranks below the others.
     */
    GAME_OBJECT_FIFTH_OPTION(1001),

    /**
     * A selected item or spell used on an NPC.
     */
    WIDGET_TARGET_ON_NPC(8),

    /**
     * An NPC's first op.
     */
    NPC_FIRST_OPTION(9),

    /**
     * An NPC's second op.
     */
    NPC_SECOND_OPTION(10),

    /**
     * An NPC's third op.
     */
    NPC_THIRD_OPTION(11),

    /**
     * An NPC's fourth op.
     */
    NPC_FOURTH_OPTION(12),

    /**
     * An NPC's fifth op.
     */
    NPC_FIFTH_OPTION(13),

    /**
     * A selected item or spell used on a player.
     */
    WIDGET_TARGET_ON_PLAYER(15),

    /**
     * A selected item or spell used on a ground item.
     */
    WIDGET_TARGET_ON_GROUND_ITEM(17),

    /**
     * A ground item's first op.
     */
    GROUND_ITEM_FIRST_OPTION(18),

    /**
     * A ground item's second op.
     */
    GROUND_ITEM_SECOND_OPTION(19),

    /**
     * A ground item's third op ("Take" on most items).
     */
    GROUND_ITEM_THIRD_OPTION(20),

    /**
     * A ground item's fourth op.
     */
    GROUND_ITEM_FOURTH_OPTION(21),

    /**
     * A ground item's fifth op.
     */
    GROUND_ITEM_FIFTH_OPTION(22),

    /**
     * Walking to the tile under the mouse ("Walk here").
     */
    WALK(23),

    /**
     * An old-style interface button of type 1 (a plain button).
     */
    WIDGET_TYPE_1(24),

    /**
     * An old-style interface button that selects a spell or item to use on something.
     */
    WIDGET_TARGET(25),

    /**
     * An old-style interface button that closes the interface.
     */
    WIDGET_CLOSE(26),

    /**
     * An old-style interface button of type 4 (a toggle).
     */
    WIDGET_TYPE_4(28),

    /**
     * An old-style interface button of type 5 (a select).
     */
    WIDGET_TYPE_5(29),

    /**
     * An old-style interface button that continues a dialogue.
     */
    WIDGET_CONTINUE(30),

    /**
     * A player's first op.
     */
    PLAYER_FIRST_OPTION(44),

    /**
     * A player's second op.
     */
    PLAYER_SECOND_OPTION(45),

    /**
     * A player's third op.
     */
    PLAYER_THIRD_OPTION(46),

    /**
     * A player's fourth op.
     */
    PLAYER_FOURTH_OPTION(47),

    /**
     * A player's fifth op.
     */
    PLAYER_FIFTH_OPTION(48),

    /**
     * A player's sixth op.
     */
    PLAYER_SIXTH_OPTION(49),

    /**
     * A player's seventh op.
     */
    PLAYER_SEVENTH_OPTION(50),

    /**
     * A player's eighth op.
     */
    PLAYER_EIGHTH_OPTION(51),

    /**
     * One of a component's first five ops; the entry's identifier is the op (1-5).
     */
    CC_OP(57),

    /**
     * A selected item or spell used on a component.
     */
    WIDGET_TARGET_ON_WIDGET(58),

    /**
     * Examining a loc.
     */
    EXAMINE_OBJECT(1002),

    /**
     * Examining an NPC.
     */
    EXAMINE_NPC(1003),

    /**
     * Examining a ground item.
     */
    EXAMINE_ITEM_GROUND(1004),

    /**
     * Closing the menu without doing anything ("Cancel").
     */
    CANCEL(1006),

    /**
     * One of a component's last five ops (6-10), which the client ranks below the others; the entry's identifier is
     * the op.
     */
    CC_OP_LOW_PRIORITY(1007),

    /**
     * A row added by a plugin ({@link Client#createMenuEntry}): clicking it runs the plugin's callback, nothing in the
     * client.
     */
    RUNELITE(1500),

    /**
     * An entry the API can't name: a subject or op this enum has no action for.
     */
    UNKNOWN(-1);

    private static final Map<Integer, MenuAction> BY_ID = new HashMap<>();

    static {
        for (MenuAction action : values()) {
            BY_ID.put(action.id, action);
        }
    }

    /**
     * The action's id.
     */
    @Getter
    private final int id;

    MenuAction(int id) {
        this.id = id;
    }

    /**
     * The action with an id.
     *
     * @param id the action id
     * @return the action, or {@link #UNKNOWN} for an id this enum doesn't have
     */
    public static MenuAction of(int id) {
        return BY_ID.getOrDefault(id, UNKNOWN);
    }
}
