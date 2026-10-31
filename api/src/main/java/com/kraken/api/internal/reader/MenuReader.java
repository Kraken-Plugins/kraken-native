package com.kraken.api.internal.reader;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.menu.MenuEdits;
import com.kraken.api.widgets.Widget;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Reads the client's minimenu (the right-click menu) behind {@code Client.minimenu}: the rows of the menu it shows,
 * whether it is open, the copy of the entry it last ran, and the options the server set for other players.
 * {@link Client} uses it for {@code getMenuEntries}, {@code isMenuOpen} and {@code getPlayerOptions}, the event source
 * for the menu events.
 *
 * <p>While the menu is closed the client rebuilds the rows every cycle from what the mouse is over; while it is open
 * they hold the opened menu. The rows are a vector of nodes {@code {list, index, children}}, top row first: a row's
 * entry is {@code [list + entries] + index * MinimenuEntry.size}, and a node without a list is a submenu group, read as
 * its first entry (the one the client's own left click runs). A component op's node keeps the rows of the sub-menu
 * the op opens as its children, each a node of the sub-menu's own list whose entry holds the sub-option index as its
 * op index; they are read as entries of the op with that sub-option, right after the op's row. Each entry is read in
 * one block.
 *
 * <p>The native entry keeps its subject kind ({@code enums.minimenuSubject}), type id, index, world tile and op index;
 * {@link #entry} works the action, identifier and parameters out of them. A row the native hook added for a plugin
 * (no subject, {@link MenuEdits#ROW_OP} as its op, its row id as the type id) reads as the plugin's own
 * {@link MenuEntry}.
 *
 * <p>The player options are a table of {@value #PLAYER_OPTIONS} eastl strings in the Minimenu object
 * ({@code Minimenu.playerOps}, op 1 first, "" for an option the server hasn't set) and a byte per option
 * ({@code Minimenu.playerOpPriorities}) that lists the option below Walk here when set.
 *
 * <p>The keys are optional: with a mapping that lacks them the menu reads empty and closed, and the player options
 * read empty.
 */
public final class MenuReader extends GameReader {

    /**
     * Most rows read; the client's menu holds far fewer.
     */
    private static final int MAX_ROWS = 500;

    /**
     * How deep a submenu group is followed to its first entry.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * Ops a component has: 0-4 are its first five, 5-9 the low-priority rest (the op index is 0-based there).
     */
    private static final int COMPONENT_OPS = 10;
    private static final int HIGH_PRIORITY_COMPONENT_OPS = 5;

    /**
     * Most sub-option rows read under one op; a sub-menu holds far fewer.
     */
    private static final int MAX_SUB_OPTIONS = 64;

    /**
     * Options the server can set for other players: op 1 to op 8.
     */
    public static final int PLAYER_OPTIONS = 8;

    private static final MenuAction[] NPC_OPS = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
    private static final MenuAction[] LOC_OPS = {MenuAction.GAME_OBJECT_FIRST_OPTION,
            MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION,
            MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION};
    private static final MenuAction[] OBJ_OPS = {MenuAction.GROUND_ITEM_FIRST_OPTION,
            MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.GROUND_ITEM_FOURTH_OPTION, MenuAction.GROUND_ITEM_FIFTH_OPTION};
    private static final MenuAction[] PLAYER_OPS = {MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
            MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
            MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION};

    /**
     * The action of an old-style interface button, by its button type ({@code IfType.event}, 1-6).
     */
    private static final MenuAction[] BUTTONS = {MenuAction.WIDGET_TYPE_1, MenuAction.WIDGET_TARGET,
            MenuAction.WIDGET_CLOSE, MenuAction.WIDGET_TYPE_4, MenuAction.WIDGET_TYPE_5, MenuAction.WIDGET_CONTINUE};

    private final Client client;
    private final boolean mapped;

    private final int cMinimenu;
    private final int mRows;
    private final int mOpen;
    private final int mClicked;
    private final int mClickCycles;
    private final int mPlayerOps;
    private final int mPlayerOpPriorities;

    private final int nodeList;
    private final int nodeIndex;
    private final int nodeChildren;
    private final int nodeSize;
    private final int listEntries;

    private final int entrySize;
    private final int eOption;
    private final int eTarget;
    private final int eTypeId;
    private final int eSubjectIndex;
    private final int eOpIndex;
    private final int eLevel;
    private final int eX;
    private final int eZ;
    private final int eSubjectType;

    private final int kindTile;
    private final int kindNpc;
    private final int kindLoc;
    private final int kindObj;
    private final int kindPlayer;
    private final int kindComponent;
    private final int opTarget;
    private final int opExamine;

    /**
     * Resolves the minimenu keys.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param client  the client the entries look their actors, widgets and the map base up in
     */
    public MenuReader(Memory memory, Mapping mapping, Client client) {
        super(memory, mapping);
        this.client = client;
        this.cMinimenu = optionalOffset("Client.minimenu");
        this.mRows = optionalOffset("Minimenu.rows");
        this.mOpen = optionalOffset("Minimenu.open");
        this.mClicked = optionalOffset("Minimenu.clicked");
        this.mClickCycles = optionalOffset("Minimenu.clickCycles");
        this.mPlayerOps = optionalOffset("Minimenu.playerOps");
        this.mPlayerOpPriorities = optionalOffset("Minimenu.playerOpPriorities");
        this.nodeList = containerOr("minimenu.node.list", -1);
        this.nodeIndex = containerOr("minimenu.node.index", -1);
        this.nodeChildren = containerOr("minimenu.node.children", -1);
        this.nodeSize = containerOr("minimenu.node.size", -1);
        this.listEntries = containerOr("minimenu.list.entries", -1);
        this.entrySize = optionalOffset("MinimenuEntry.size");
        this.eOption = optionalOffset("MinimenuEntry.option");
        this.eTarget = optionalOffset("MinimenuEntry.target");
        this.eTypeId = optionalOffset("MinimenuEntry.typeId");
        this.eSubjectIndex = optionalOffset("MinimenuEntry.subjectIndex");
        this.eOpIndex = optionalOffset("MinimenuEntry.opIndex");
        this.eLevel = optionalOffset("MinimenuEntry.level");
        this.eX = optionalOffset("MinimenuEntry.x");
        this.eZ = optionalOffset("MinimenuEntry.z");
        this.eSubjectType = optionalOffset("MinimenuEntry.subjectType");

        Map<String, Integer> kinds = mapping.enumTable("minimenuSubject");
        this.kindTile = kinds.getOrDefault("TILE", -1);
        this.kindNpc = kinds.getOrDefault("NPC", -1);
        this.kindLoc = kinds.getOrDefault("LOC", -1);
        this.kindObj = kinds.getOrDefault("OBJ", -1);
        this.kindPlayer = kinds.getOrDefault("PLAYER", -1);
        this.kindComponent = kinds.getOrDefault("COMPONENT", -1);
        Map<String, Integer> ops = mapping.enumTable("minimenuOp");
        this.opTarget = ops.getOrDefault("TARGET", -1);
        this.opExamine = ops.getOrDefault("EXAMINE", Integer.MIN_VALUE);

        int[] keys = {cMinimenu, mRows, mOpen, mClicked, mClickCycles, nodeList, nodeIndex, nodeChildren, nodeSize,
                listEntries, entrySize, eOption, eTarget, eTypeId, eSubjectIndex, eOpIndex, eLevel, eX, eZ,
                eSubjectType};
        this.mapped = IntStream.of(keys).allMatch(off -> off >= 0) && nodeSize > 0 && entrySize > 0 && !kinds.isEmpty();
    }

    /**
     * The menu's rows, top row (the one a left click runs) first, each entry read now.
     *
     * @return the entries; empty if the menu can't be read or the mapping lacks the minimenu keys
     */
    public List<MenuEntry> rows() {
        long menu = menu();
        if (menu == 0) {
            return List.of();
        }
        long begin = safePointer(menu + mRows);
        long end = safePointer(menu + mRows + 8);
        long span = end - begin;
        if (begin == 0 || span <= 0 || span % nodeSize != 0 || span / nodeSize > MAX_ROWS) {
            return List.of();
        }
        int count = (int) (span / nodeSize);
        ByteBuffer nodes = readBlock(begin, count * nodeSize);
        if (nodes == null) {
            return List.of();
        }
        MapCoord base = client.mapBase();
        List<MenuEntry> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long entry = leaf(nodes, i * nodeSize);
            MenuEntry read = entry == 0 ? null : entry(entry, base);
            if (read != null) {
                rows.add(read);
                if (nodes.getLong(i * nodeSize + nodeList) != 0) {
                    rows.addAll(subOptions(read, nodes.getLong(i * nodeSize + nodeChildren), base));
                }
            }
        }
        return rows;
    }

    /**
     * The sub-option rows under a component op's row: its node's children, each read as the op's entry with the
     * sub-option the child's entry holds as its op index.
     *
     * @param parent   the op's entry
     * @param children the node's children vector, or 0 for none
     * @param base     the map base, or null if unknown
     * @return the sub-option entries, top first; empty if the row isn't a component op or has no children
     */
    private List<MenuEntry> subOptions(MenuEntry parent, long children, MapCoord base) {
        if (children == 0 || parent.getType() != MenuAction.CC_OP && parent.getType() != MenuAction.CC_OP_LOW_PRIORITY) {
            return List.of();
        }
        long first = safePointer(children);
        long last = safePointer(children + 8);
        long span = last - first;
        if (first == 0 || span <= 0 || span % nodeSize != 0 || span / nodeSize > MAX_SUB_OPTIONS) {
            return List.of();
        }
        int count = (int) (span / nodeSize);
        ByteBuffer nodes = readBlock(first, count * nodeSize);
        if (nodes == null) {
            return List.of();
        }
        List<MenuEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long entry = leaf(nodes, i * nodeSize);
            MenuEntry row = entry == 0 ? null : entry(entry, base);
            if (row != null && row.getParam1() == parent.getParam1() && row.getParam0() == parent.getParam0()) {
                out.add(new MenuEntry(client, row.getOption(), row.getTarget(), parent.getIdentifier(),
                        parent.getType(), parent.getParam0(), parent.getParam1(), null, subOp(entry)));
            }
        }
        return out;
    }

    /**
     * The sub-option a sub-menu row's entry names: its op index plus one.
     *
     * @param entry the entry
     * @return the sub-option, 1-based, or 0 if it can't be read
     */
    private int subOp(long entry) {
        int index = safeInt(entry + eOpIndex, -1);
        return index < 0 ? 0 : index + 1;
    }

    /**
     * Whether a menu is open: the client's open view is set from the right click (or the menu key) until the menu
     * closes.
     *
     * @return true while a menu is open; false if it can't be read
     */
    public boolean isOpen() {
        long menu = menu();
        return menu != 0 && safePointer(menu + mOpen) != 0;
    }

    /**
     * Client cycles since an entry last ran: running one zeroes the counter, and every client cycle increments it.
     *
     * @return the cycles, or -1 if they can't be read
     */
    public int clickCycles() {
        long menu = menu();
        return menu == 0 ? -1 : safeInt(menu + mClickCycles, -1);
    }

    /**
     * The client's copy of the entry it last ran, taken right before it raises the op event.
     *
     * @return the entry, or null if it can't be read
     */
    public MenuEntry clicked() {
        long menu = menu();
        return menu == 0 ? null : entry(menu + mClicked, client.mapBase());
    }

    /**
     * The options the server set for right-clicking other players ("Trade with", "Follow", "Attack" in a PvP area),
     * read in one block.
     *
     * @return {@value #PLAYER_OPTIONS} entries, index 0 being op 1, null for an option the server hasn't set; empty if
     *         the table can't be read or the mapping lacks {@code Minimenu.playerOps}
     */
    public String[] playerOptions() {
        long owner = owner();
        int size = structs.stringSize();
        ByteBuffer block = owner == 0 || mPlayerOps < 0 ? null : readBlock(owner + mPlayerOps, PLAYER_OPTIONS * size);
        if (block == null) {
            return new String[0];
        }
        String[] options = new String[PLAYER_OPTIONS];
        for (int i = 0; i < PLAYER_OPTIONS; i++) {
            String option = structs.string(block.array(), owner + mPlayerOps, i * size);
            options[i] = option.isEmpty() ? null : option;
        }
        return options;
    }

    /**
     * Which player options the client lists below Walk here: those the server sent with a priority flag of 0. The
     * client gives "Attack" its own place whatever this says.
     *
     * @return {@value #PLAYER_OPTIONS} entries, index 0 being op 1, true for a low-priority option; empty if the bytes
     *         can't be read or the mapping lacks {@code Minimenu.playerOpPriorities}
     */
    public boolean[] playerOptionPriorities() {
        long owner = owner();
        ByteBuffer block = owner == 0 || mPlayerOpPriorities < 0 ? null
                : readBlock(owner + mPlayerOpPriorities, PLAYER_OPTIONS);
        if (block == null) {
            return new boolean[0];
        }
        boolean[] low = new boolean[PLAYER_OPTIONS];
        for (int i = 0; i < PLAYER_OPTIONS; i++) {
            low[i] = block.get(i) != 0;
        }
        return low;
    }

    /**
     * The Minimenu object, when every key of the menu's rows is mapped.
     *
     * @return its address, or 0 if unreadable or not mapped
     */
    private long menu() {
        return mapped ? owner() : 0;
    }

    /**
     * The Minimenu object, needing only {@code Client.minimenu}.
     *
     * @return its address, or 0 if unreadable or not mapped
     */
    private long owner() {
        if (cMinimenu < 0) {
            return 0;
        }
        long base = clientBase();
        return base == 0 ? 0 : safePointer(base + cMinimenu);
    }

    /**
     * The entry a node shows: its own, or for a submenu group its first child's.
     *
     * @param nodes  the block the node was read in
     * @param offset where the node starts in it
     * @return the entry's address, or 0 if it can't be found
     */
    private long leaf(ByteBuffer nodes, int offset) {
        long list = nodes.getLong(offset + nodeList);
        long index = nodes.getLong(offset + nodeIndex);
        long children = nodes.getLong(offset + nodeChildren);
        for (int depth = 0; list == 0 && depth < MAX_DEPTH; depth++) {
            long first = children == 0 ? 0 : safePointer(children);
            long last = children == 0 ? 0 : safePointer(children + 8);
            if (first == 0 || last - first < nodeSize) {
                return 0;
            }
            ByteBuffer node = readBlock(first, nodeSize);
            if (node == null) {
                return 0;
            }
            list = node.getLong(nodeList);
            index = node.getLong(nodeIndex);
            children = node.getLong(nodeChildren);
        }
        if (list == 0 || index < 0 || index > MAX_ROWS) {
            return 0;
        }
        long entries = safePointer(list + listEntries);
        return entries == 0 ? 0 : entries + index * entrySize;
    }

    /**
     * Reads one native entry and works out its action.
     *
     * @param address the entry
     * @param base    the map base the scene parameters are taken from, or null if unknown
     * @return the entry, or null if it can't be read
     */
    private MenuEntry entry(long address, MapCoord base) {
        ByteBuffer block = readBlock(address, entrySize);
        if (block == null) {
            return null;
        }
        byte[] bytes = block.array();
        String option = structs.string(bytes, address, eOption);
        String target = structs.string(bytes, address, eTarget);
        int kind = bytes[eSubjectType] & 0xff;
        int op = block.getInt(eOpIndex);
        int typeId = block.getInt(eTypeId);
        int index = block.getInt(eSubjectIndex);
        WorldPoint tile = new WorldPoint(block.getInt(eX), block.getInt(eZ), block.getInt(eLevel));
        int sceneX = base == null ? -1 : tile.getX() - base.x();
        int sceneY = base == null ? -1 : tile.getY() - base.z();

        if (kind == kindNpc) {
            return new MenuEntry(client, option, target, index, action(op, MenuAction.WIDGET_TARGET_ON_NPC,
                    MenuAction.EXAMINE_NPC, NPC_OPS), 0, 0, null);
        }
        if (kind == kindPlayer) {
            return new MenuEntry(client, option, target, index, action(op, MenuAction.WIDGET_TARGET_ON_PLAYER,
                    MenuAction.UNKNOWN, PLAYER_OPS), 0, 0, null);
        }
        if (kind == kindLoc) {
            return new MenuEntry(client, option, target, typeId, action(op, MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
                    MenuAction.EXAMINE_OBJECT, LOC_OPS), sceneX, sceneY, tile);
        }
        if (kind == kindObj) {
            return new MenuEntry(client, option, target, typeId, action(op, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
                    MenuAction.EXAMINE_ITEM_GROUND, OBJ_OPS), sceneX, sceneY, tile);
        }
        if (kind == kindTile) {
            return new MenuEntry(client, option, target, 0, MenuAction.WALK, sceneX, sceneY, tile);
        }
        if (kind == kindComponent) {
            return component(option, target, op, typeId, index);
        }
        if (op == MenuEdits.ROW_OP) {
            MenuEntry created = client.getMenuEdits().created(typeId);
            if (created != null) {
                return created;
            }
            return new MenuEntry(client, option, target, typeId, MenuAction.RUNELITE, 0, 0, null);
        }
        MenuAction type = "Cancel".equals(option) ? MenuAction.CANCEL : MenuAction.UNKNOWN;
        return new MenuEntry(client, option, target, 0, type, 0, 0, null);
    }

    /**
     * A component entry: an op of the component (the op index is 0-based, ops past the fifth rank low), a selected
     * item or spell used on it, or an old-style button, whose action is the component's button type.
     *
     * @param option   the option text
     * @param target   the target text
     * @param op       the entry's op index
     * @param packedId the component's packed id (the entry's type id)
     * @param subId    the component's dynamic child index, -1 for none (the entry's index)
     * @return the entry
     */
    private MenuEntry component(String option, String target, int op, int packedId, int subId) {
        if (op == opTarget) {
            return new MenuEntry(client, option, target, 0, MenuAction.WIDGET_TARGET_ON_WIDGET, subId, packedId, null);
        }
        if (op >= 0 && op < COMPONENT_OPS) {
            MenuAction type = op < HIGH_PRIORITY_COMPONENT_OPS ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY;
            return new MenuEntry(client, option, target, op + 1, type, subId, packedId, null);
        }
        Widget widget = client.getWidget(packedId);
        if (widget != null && subId >= 0) {
            widget = widget.getChild(subId);
        }
        int button = widget == null ? 0 : widget.getEvent();
        MenuAction type = button >= 1 && button <= BUTTONS.length ? BUTTONS[button - 1] : MenuAction.UNKNOWN;
        return new MenuEntry(client, option, target, 0, type, subId, packedId, null);
    }

    /**
     * The action of a subject's op: its targeted and Examine actions for those op values, else its n-th op (1-based).
     *
     * @param op       the entry's op index
     * @param targeted the action of a selected item or spell used on the subject
     * @param examine  the subject's Examine action ({@link MenuAction#UNKNOWN} if it has none)
     * @param numbered the subject's ops in order
     * @return the action, {@link MenuAction#UNKNOWN} for an op the subject doesn't have
     */
    private MenuAction action(int op, MenuAction targeted, MenuAction examine, MenuAction[] numbered) {
        if (op == opTarget) {
            return targeted;
        }
        if (op == opExamine) {
            return examine;
        }
        return op >= 1 && op <= numbered.length ? numbered[op - 1] : MenuAction.UNKNOWN;
    }
}
