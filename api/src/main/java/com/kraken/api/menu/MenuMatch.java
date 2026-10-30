package com.kraken.api.menu;

import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;

/**
 * Which rows of the menu a {@link MenuRule} is about. Every part is optional and the parts set must all agree with a
 * row: its subject kind, its action (with the op number for a component op), the type id of what it is about (an
 * NPC type, loc or item id, a component's packed id), the index (an NPC's or player's index, a component's slot),
 * the interface a component belongs to, the world tile, and its option and target texts. {@link #of} is a row's
 * identity: what the client's entry records about its subject and op, without the texts, so a row still matches
 * after its text was rewritten.
 *
 * <p>Immutable: each method returns a new match with one more part.
 *
 * @param kind       the subject kind, or null for any
 * @param action     the action, or null for any; {@link MenuAction#WALK} matches a tile row of any op
 * @param identifier the op number of a {@link MenuAction#CC_OP} or {@link MenuAction#CC_OP_LOW_PRIORITY} action,
 *                   or null for any op of it; ignored for other actions
 * @param typeId     the type id, or null for any
 * @param index      the index, or null for any
 * @param group      the interface id of a component row ({@code packed id >> 16}), or null for any
 * @param tile       the world tile, or null for any
 * @param option     the option pattern, or null for any
 * @param target     the target pattern, or null for any
 * @param subMenuRows true to match the rows of the sub-menus under component ops instead of the top rows
 */
public record MenuMatch(MenuSubject kind, MenuAction action, Integer identifier, Integer typeId, Integer index,
                        Integer group, WorldPoint tile, TextMatch option, TextMatch target, boolean subMenuRows) {

    private static final MenuMatch ANY = new MenuMatch(null, null, null, null, null, null, null, null, null, false);

    /**
     * A match on every row.
     *
     * @return the match
     */
    public static MenuMatch any() {
        return ANY;
    }

    /**
     * A row's identity: its kind, action (and op), type id, index and tile, as the client's entry holds them. The
     * texts are left out, so the row keeps matching after a rule rewrote them. A "Cancel" row or another row without
     * a subject matches by its option text instead, as the entry records nothing else.
     *
     * @param entry the row
     * @return the match
     */
    public static MenuMatch of(MenuEntry entry) {
        MenuMatch match = subject(entry);
        if (match.kind() == MenuSubject.NONE) {
            return match.option(TextMatch.exact(entry.getOption()));
        }
        Integer op = entry.getType() == MenuAction.CC_OP || entry.getType() == MenuAction.CC_OP_LOW_PRIORITY
                ? entry.getIdentifier() : null;
        return new MenuMatch(match.kind(), entry.getType(), op, match.typeId(), match.index(), null, match.tile(),
                null, null, false);
    }

    /**
     * What a row is about, whatever its op: its kind, type id, index and tile. Every row of the same NPC, loc,
     * ground item, player or component slot matches.
     *
     * @param entry the row
     * @return the match
     */
    public static MenuMatch subject(MenuEntry entry) {
        MenuSubject kind = kindOf(entry.getType());
        return switch (kind) {
            case NPC, PLAYER -> new MenuMatch(kind, null, null, null, entry.getIdentifier(), null, null, null, null,
                    false);
            case LOC, OBJ -> new MenuMatch(kind, null, null, entry.getIdentifier(), null, null,
                    entry.getWorldLocation(), null, null, false);
            case TILE -> new MenuMatch(kind, null, null, null, null, null, entry.getWorldLocation(), null, null,
                    false);
            case COMPONENT -> new MenuMatch(kind, null, null, entry.getParam1(), entry.getParam0(), null, null, null,
                    null, false);
            case NONE -> new MenuMatch(kind, null, null, null, null, null, null, null, null, false);
        };
    }

    /**
     * The subject kind an action is about.
     *
     * @param action the action
     * @return the kind; {@link MenuSubject#NONE} for Cancel, a plugin's row and the unknown action
     */
    public static MenuSubject kindOf(MenuAction action) {
        return switch (action) {
            case WIDGET_TARGET_ON_NPC, NPC_FIRST_OPTION, NPC_SECOND_OPTION, NPC_THIRD_OPTION, NPC_FOURTH_OPTION,
                 NPC_FIFTH_OPTION, EXAMINE_NPC -> MenuSubject.NPC;
            case WIDGET_TARGET_ON_PLAYER, PLAYER_FIRST_OPTION, PLAYER_SECOND_OPTION, PLAYER_THIRD_OPTION,
                 PLAYER_FOURTH_OPTION, PLAYER_FIFTH_OPTION, PLAYER_SIXTH_OPTION, PLAYER_SEVENTH_OPTION,
                 PLAYER_EIGHTH_OPTION -> MenuSubject.PLAYER;
            case WIDGET_TARGET_ON_GAME_OBJECT, GAME_OBJECT_FIRST_OPTION, GAME_OBJECT_SECOND_OPTION,
                 GAME_OBJECT_THIRD_OPTION, GAME_OBJECT_FOURTH_OPTION, GAME_OBJECT_FIFTH_OPTION, EXAMINE_OBJECT ->
                    MenuSubject.LOC;
            case WIDGET_TARGET_ON_GROUND_ITEM, GROUND_ITEM_FIRST_OPTION, GROUND_ITEM_SECOND_OPTION,
                 GROUND_ITEM_THIRD_OPTION, GROUND_ITEM_FOURTH_OPTION, GROUND_ITEM_FIFTH_OPTION,
                 EXAMINE_ITEM_GROUND -> MenuSubject.OBJ;
            case WALK -> MenuSubject.TILE;
            case CC_OP, CC_OP_LOW_PRIORITY, WIDGET_TARGET_ON_WIDGET, WIDGET_TYPE_1, WIDGET_TARGET, WIDGET_CLOSE,
                 WIDGET_TYPE_4, WIDGET_TYPE_5, WIDGET_CONTINUE -> MenuSubject.COMPONENT;
            case CANCEL, RUNELITE, UNKNOWN -> MenuSubject.NONE;
        };
    }

    /**
     * Narrows to rows of a subject kind.
     *
     * @param subject the kind
     * @return the match
     */
    public MenuMatch kind(MenuSubject subject) {
        return new MenuMatch(subject, action, identifier, typeId, index, group, tile, option, target, subMenuRows);
    }

    /**
     * Narrows to rows running an action, and to its kind. A component op matches whatever its op number.
     *
     * @param menuAction the action
     * @return the match
     */
    public MenuMatch action(MenuAction menuAction) {
        return new MenuMatch(kindOf(menuAction), menuAction, null, typeId, index, group, tile, option, target,
                subMenuRows);
    }

    /**
     * Narrows to rows running an action with an identifier: the op number (1 to 10) of a component op. For other
     * actions the identifier is the type id or index, which {@link #typeId} and {@link #index} narrow by.
     *
     * @param menuAction the action
     * @param op         the op number of a component op
     * @return the match
     */
    public MenuMatch action(MenuAction menuAction, int op) {
        return new MenuMatch(kindOf(menuAction), menuAction, op, typeId, index, group, tile, option, target,
                subMenuRows);
    }

    /**
     * Narrows to rows about a type id: an NPC type, a loc id, an item id or a component's packed id.
     *
     * @param id the type id
     * @return the match
     */
    public MenuMatch typeId(int id) {
        return new MenuMatch(kind, action, identifier, id, index, group, tile, option, target, subMenuRows);
    }

    /**
     * Narrows to rows about an index: an NPC's or player's index, or a component's slot (-1 for a static component).
     *
     * @param subjectIndex the index
     * @return the match
     */
    public MenuMatch index(int subjectIndex) {
        return new MenuMatch(kind, action, identifier, typeId, subjectIndex, group, tile, option, target, subMenuRows);
    }

    /**
     * Narrows to component rows of an interface.
     *
     * @param interfaceId the interface id ({@code packed id >> 16})
     * @return the match
     */
    public MenuMatch group(int interfaceId) {
        return new MenuMatch(kind, action, identifier, typeId, index, interfaceId, tile, option, target, subMenuRows);
    }

    /**
     * Narrows to rows about a world tile.
     *
     * @param worldTile the tile
     * @return the match
     */
    public MenuMatch tile(WorldPoint worldTile) {
        return new MenuMatch(kind, action, identifier, typeId, index, group, worldTile, option, target, subMenuRows);
    }

    /**
     * Narrows to rows whose option matches a pattern.
     *
     * @param pattern the pattern
     * @return the match
     */
    public MenuMatch option(TextMatch pattern) {
        return new MenuMatch(kind, action, identifier, typeId, index, group, tile, pattern, target, subMenuRows);
    }

    /**
     * Narrows to rows whose target matches a pattern.
     *
     * @param pattern the pattern
     * @return the match
     */
    public MenuMatch target(TextMatch pattern) {
        return new MenuMatch(kind, action, identifier, typeId, index, group, tile, option, pattern, subMenuRows);
    }

    /**
     * Matches the rows of the sub-menus under component ops instead of the top rows.
     *
     * @return the match
     */
    public MenuMatch inSubMenus() {
        return new MenuMatch(kind, action, identifier, typeId, index, group, tile, option, target, true);
    }
}
