package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeInvoker;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.widgets.Widget;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs menu actions the way a click on the minimenu would, without the menu: the client's own row bodies, called on
 * the game thread by the native hook with the arguments a row would have captured, send the op packet and start the
 * client's own interaction (facing and pathing to the target, the destination marker of a walk). A mouse click can go
 * first, as the game's network step sends one for a real click: {@link #click} sends the click packet and the action
 * in one batch, in that order.
 *
 * <p>What is covered follows what the menu builders capture ({@link #isSupported}): an NPC's or player's op by index,
 * a loc's or ground item's op by id and world tile, Walk here by world tile ({@link #walk}), a component's op by
 * packed id, slot and item ({@link MenuAction#CC_OP}), with a sub-option of the op when a sub-menu offers them,
 * selecting an item or spell ({@link MenuAction#WIDGET_TARGET}) and using the selection on a component, NPC, loc,
 * ground item or player (the {@code WIDGET_TARGET_ON_*} actions; {@link #useItemOnItem} and {@link #useItemOn} select
 * an item and use it in one batch, {@link #castSpellOn} does the same for a spell), the old-style buttons (a plain
 * button, {@link MenuAction#WIDGET_TYPE_1}; a toggle, {@link MenuAction#WIDGET_TYPE_4}; a set,
 * {@link MenuAction#WIDGET_TYPE_5}; a close button, {@link MenuAction#WIDGET_CLOSE}, {@link #closeInterface}) and a
 * pause button ({@link MenuAction#WIDGET_CONTINUE}, {@link #resumePause}). The dialogs the server asks the player to
 * answer are answered with the packets the client's scripts send ({@link #resumeCountDialog},
 * {@link #resumeNameDialog}, {@link #resumeStringDialog}, {@link #resumeObjDialog}), built with the client's own
 * packet helpers. Examine has no body that sends anything, and the minimap walk's packet is built inline by the
 * client's minimap click handler, so neither is sent.
 *
 * <p>Calls only reach the client while its native hook is connected ({@link #isAvailable()}); actions asked for before
 * that are dropped, not queued, since an action is only meant for the moment it is asked.
 *
 * <p>Thread-safe.
 */
public final class Actions {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The most milliseconds the click packet reports since the last click.
     */
    private static final int MAX_CLICK_DELAY = 0x7fff;

    /**
     * Where the packet struct the packet calls build starts in the batch's scratch block, and its buffer inside it.
     */
    private static final int PACKET = 0;
    private static final int PACKET_BUFFER = PACKET + 8;

    /**
     * The most characters a dialog answer can hold: its size byte counts them plus the terminator.
     */
    private static final int MAX_DIALOG_TEXT = 254;

    private static final int NPC_OPS = 5;
    private static final int LOC_OPS = 5;
    private static final int OBJ_OPS = 5;
    private static final int PLAYER_OPS = 8;

    private static final List<String> FUNCTIONS = List.of("menuNpcOp", "menuLocOp", "menuObjOp", "menuPlayerOp",
            "menuWalk", "menuNpcTargetOp", "menuLocTargetOp", "menuObjTargetOp", "menuPlayerTargetOp",
            "menuComponentOp", "menuComponentTargetOp", "menuComponentSelect", "menuComponentPause",
            "menuComponentButton", "menuComponentToggle", "menuComponentSet", "menuComponentClose",
            "packetCreate", "packetWriteByte", "packetWriteShort", "packetWriteInt", "packetWriteString", "packetSend",
            "packetRelease");
    private static final List<String> OPCODES = List.of("EVENT_MOUSE_CLICK", "RESUME_P_COUNTDIALOG",
            "RESUME_P_NAMEDIALOG", "RESUME_P_STRINGDIALOG", "RESUME_P_OBJDIALOG");

    private final Client client;
    private final Map<String, Long> functions = new HashMap<>();
    private final Map<String, Integer> opcodes = new HashMap<>();
    private final Map<MenuAction, Op> ops = new EnumMap<>(MenuAction.class);
    private final int connection;
    private final int outCipher;

    private NativeInvoker invoker;
    private boolean sendFailed;
    private long lastClickNanos;

    /**
     * Resolves the row bodies, packet helpers and opcodes once.
     *
     * @param client  the client, for the map base when an action names scene coordinates and for a component's item
     * @param mapping the client build's mapping
     */
    public Actions(Client client, Mapping mapping) {
        this.client = Objects.requireNonNull(client, "client");
        for (String key : FUNCTIONS) {
            if (mapping.hasFunction(key)) {
                functions.put(key, mapping.function(key));
            }
        }
        for (String name : OPCODES) {
            int opcode = mapping.enumValue("clientProt", name, -1);
            if (opcode >= 0) {
                opcodes.put(name, opcode);
            }
        }
        this.connection = mapping.has("Client.connection") ? mapping.offset("Client.connection") : -1;
        this.outCipher = mapping.has("Connection.outCipher") ? mapping.offset("Connection.outCipher") : -1;
        MenuAction[] npc = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
                MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
        MenuAction[] loc = {MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
                MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
                MenuAction.GAME_OBJECT_FIFTH_OPTION};
        MenuAction[] obj = {MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
                MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
                MenuAction.GROUND_ITEM_FIFTH_OPTION};
        MenuAction[] player = {MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
                MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
                MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION};
        for (int i = 0; i < NPC_OPS; i++) {
            ops.put(npc[i], new Op(Subject.NPC, i + 1));
        }
        for (int i = 0; i < LOC_OPS; i++) {
            ops.put(loc[i], new Op(Subject.LOC, i + 1));
        }
        for (int i = 0; i < OBJ_OPS; i++) {
            ops.put(obj[i], new Op(Subject.OBJ, i + 1));
        }
        for (int i = 0; i < PLAYER_OPS; i++) {
            ops.put(player[i], new Op(Subject.PLAYER, i + 1));
        }
        ops.put(MenuAction.WALK, new Op(Subject.TILE, 0));
        ops.put(MenuAction.CC_OP, new Op(Subject.COMPONENT, 0));
        ops.put(MenuAction.CC_OP_LOW_PRIORITY, new Op(Subject.COMPONENT, 0));
        ops.put(MenuAction.WIDGET_TARGET, new Op(Subject.COMPONENT_SELECT, 0));
        ops.put(MenuAction.WIDGET_CONTINUE, new Op(Subject.COMPONENT_PAUSE, 0));
        ops.put(MenuAction.WIDGET_TARGET_ON_WIDGET, new Op(Subject.COMPONENT_TARGET, 0));
        ops.put(MenuAction.WIDGET_TARGET_ON_NPC, new Op(Subject.NPC_TARGET, 0));
        ops.put(MenuAction.WIDGET_TARGET_ON_PLAYER, new Op(Subject.PLAYER_TARGET, 0));
        ops.put(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, new Op(Subject.LOC_TARGET, 0));
        ops.put(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, new Op(Subject.OBJ_TARGET, 0));
        ops.put(MenuAction.WIDGET_TYPE_1, new Op(Subject.COMPONENT_BUTTON, 0));
        ops.put(MenuAction.WIDGET_TYPE_4, new Op(Subject.COMPONENT_TOGGLE, 0));
        ops.put(MenuAction.WIDGET_TYPE_5, new Op(Subject.COMPONENT_SET, 0));
        ops.put(MenuAction.WIDGET_CLOSE, new Op(Subject.COMPONENT_CLOSE, 0));
    }

    /**
     * Whether actions reach the client now: the hook is connected.
     *
     * @return true while calls reach the client
     */
    public synchronized boolean isAvailable() {
        return invoker != null;
    }

    /**
     * Whether this build's mapping has the row body an action needs. An action without one is never sent, hook or
     * not.
     *
     * @param action the action
     * @return true if the action can be run once the hook connects
     */
    public boolean isSupported(MenuAction action) {
        Op op = ops.get(action);
        return op != null && functions.containsKey(op.subject().function());
    }

    /**
     * Whether this build's mapping has the packet helpers and the mouse click opcode, without which no click packet
     * is sent (an action asked for with a click still runs).
     *
     * @return true if click packets can be built
     */
    public boolean isMouseClickSupported() {
        return hasPacketHelpers() && functions.containsKey("packetWriteShort") && opcodes.containsKey("EVENT_MOUSE_CLICK");
    }

    /**
     * Whether this build's mapping has the packet helpers, the writers and the opcodes the dialog answers need
     * ({@link #resumeCountDialog}, {@link #resumeNameDialog}, {@link #resumeStringDialog}, {@link #resumeObjDialog}).
     *
     * @return true if the dialog packets can be built
     */
    public boolean isDialogSupported() {
        return hasPacketHelpers() && functions.keySet().containsAll(List.of("packetWriteByte", "packetWriteShort",
                "packetWriteInt", "packetWriteString"))
                && opcodes.keySet().containsAll(List.of("RESUME_P_COUNTDIALOG", "RESUME_P_NAMEDIALOG",
                "RESUME_P_STRINGDIALOG", "RESUME_P_OBJDIALOG"));
    }

    private boolean hasPacketHelpers() {
        return connection >= 0 && outCipher >= 0 && functions.containsKey("packetCreate")
                && functions.containsKey("packetSend") && functions.containsKey("packetRelease");
    }

    /**
     * Sends the mouse click packet the client sends for a click: the milliseconds since the last click sent this way
     * (at most 32767, which a first click reports), which button, and where on the canvas.
     *
     * @param canvasX     the click's x on the game canvas
     * @param canvasY     the click's y on the game canvas
     * @param rightButton true for the right button, false for the left
     */
    public synchronized void mouseClick(int canvasX, int canvasY, boolean rightButton) {
        send(mouseClickCalls(canvasX, canvasY, rightButton));
    }

    /**
     * Walks to a world tile as Walk here on it would: the client sends the walk packet, with the ctrl and shift keys'
     * state as it reads them, and marks the destination. A tile equal to the last one walked to this way is skipped
     * by the client.
     *
     * @param tile the destination, in world coordinates
     * @return true if the walk was sent; false if Walk here isn't supported or the hook isn't connected
     */
    public synchronized boolean walk(WorldPoint tile) {
        return send(actionCall(0, 0, MenuAction.WALK, 0, -1, 0, tile));
    }

    /**
     * Walks to a world tile; see {@link #walk(WorldPoint)}.
     *
     * @param worldX the destination's world x
     * @param worldY the destination's world y
     * @param plane  the destination's plane
     * @return true if the walk was sent
     */
    public synchronized boolean walk(int worldX, int worldY, int plane) {
        return walk(new WorldPoint(worldX, worldY, plane));
    }

    /**
     * Runs a menu entry's action as a click on it would, without the mouse click packet. A component entry's item is
     * the one its widget holds; a sub-option row's entry runs its op with that sub-option.
     *
     * @param entry the entry, from {@link Client#getMenuEntries()} or a menu event
     * @return true if the action was sent; false if it isn't supported, the hook isn't connected or the entry has no
     * world tile its subject kind needs
     */
    public synchronized boolean menuAction(MenuEntry entry) {
        return send(entryCall(entry));
    }

    /**
     * Runs a menu action as a click on its entry would, without the mouse click packet. The parameters are the entry's
     * ({@link MenuEntry}): for an NPC or player action the identifier is the index; for a loc or ground item action
     * the identifier is the loc or item id and the parameters are the tile's scene x and y, on the top-level world's
     * plane; for Walk here the parameters are the tile's scene x and y; for a component action the first parameter
     * is the slot (-1 for none), the second the component's packed id and, for {@link MenuAction#CC_OP}, the
     * identifier is the op (1-based). A component's item is read from its widget; a ground item's count, which only
     * the fifth op's case passes on, is sent as 0. The old-style button actions ({@link MenuAction#WIDGET_TYPE_1},
     * {@link MenuAction#WIDGET_TYPE_4}, {@link MenuAction#WIDGET_TYPE_5}) take the component's packed id; the close
     * button ({@link MenuAction#WIDGET_CLOSE}) takes nothing.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean menuAction(int param0, int param1, MenuAction action, int identifier) {
        return send(actionCall(param0, param1, action, identifier, -1, 0, null));
    }

    /**
     * Runs a menu action with the item a component action names; otherwise as
     * {@link #menuAction(int, int, MenuAction, int)}.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param itemId     the component's item id, or -1 to read it from the widget
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean menuAction(int param0, int param1, MenuAction action, int identifier, int itemId) {
        return send(actionCall(param0, param1, action, identifier, itemId, 0, null));
    }

    /**
     * Runs a component op with one of its sub-options, the rows a sub-menu opens under the op
     * ({@link Widget#getSubOptions}); otherwise as {@link #menuAction(int, int, MenuAction, int, int)}. The client
     * sends the sub-option op packet, which names the op and the sub-option, in place of the plain op packet.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param itemId     the component's item id, or -1 to read it from the widget
     * @param subOp      the sub-option, 1-based, or 0 for the op itself; only a component op takes one
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean menuAction(int param0, int param1, MenuAction action, int identifier, int itemId,
                                           int subOp) {
        return send(actionCall(param0, param1, action, identifier, itemId, subOp, null));
    }

    /**
     * Sends a mouse click and then runs an entry's action, as a click on the entry does: the click packet first, the
     * op packet right after, in one batch on the game thread.
     *
     * @param canvasX the click's x on the game canvas
     * @param canvasY the click's y on the game canvas
     * @param entry   the entry
     * @return true if the action was sent (with the click when {@link #isMouseClickSupported()}); false if the action
     * isn't supported, the hook isn't connected or the entry has no world tile its subject kind needs
     */
    public synchronized boolean click(int canvasX, int canvasY, MenuEntry entry) {
        return clickAndRun(canvasX, canvasY, entryCall(entry));
    }

    /**
     * Sends a mouse click and then runs an action, as a click on its entry does. The parameters are as for
     * {@link #menuAction(int, int, MenuAction, int)}.
     *
     * @param canvasX    the click's x on the game canvas
     * @param canvasY    the click's y on the game canvas
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean click(int canvasX, int canvasY, int param0, int param1, MenuAction action,
                                      int identifier) {
        return clickAndRun(canvasX, canvasY, actionCall(param0, param1, action, identifier, -1, 0, null));
    }

    /**
     * Sends a mouse click and then runs an action with the item a component action names. The parameters are as for
     * {@link #menuAction(int, int, MenuAction, int, int)}.
     *
     * @param canvasX    the click's x on the game canvas
     * @param canvasY    the click's y on the game canvas
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param itemId     the component's item id, or -1 to read it from the widget
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean click(int canvasX, int canvasY, int param0, int param1, MenuAction action,
                                      int identifier, int itemId) {
        return clickAndRun(canvasX, canvasY, actionCall(param0, param1, action, identifier, itemId, 0, null));
    }

    /**
     * Sends a mouse click and then runs a component op with one of its sub-options. The parameters are as for
     * {@link #menuAction(int, int, MenuAction, int, int, int)}.
     *
     * @param canvasX    the click's x on the game canvas
     * @param canvasY    the click's y on the game canvas
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param itemId     the component's item id, or -1 to read it from the widget
     * @param subOp      the sub-option, 1-based, or 0 for the op itself
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean click(int canvasX, int canvasY, int param0, int param1, MenuAction action,
                                      int identifier, int itemId, int subOp) {
        return clickAndRun(canvasX, canvasY, actionCall(param0, param1, action, identifier, itemId, subOp, null));
    }

    /**
     * Uses an item on another in the same component, as Use on the first and a click on the second do: the first is
     * selected and the targeted op is run on the second, in one batch on the game thread.
     *
     * @param componentId  the packed id of the component holding both items (an inventory)
     * @param slot         the slot of the item to use
     * @param itemId       the id of the item to use
     * @param targetSlot   the slot of the item to use it on
     * @param targetItemId the id of the item to use it on
     * @return true if both were sent; false if selecting or the targeted op isn't supported or the hook isn't connected
     */
    public synchronized boolean useItemOnItem(int componentId, int slot, int itemId, int targetSlot,
                                              int targetItemId) {
        return useItemOn(componentId, slot, itemId, targetSlot, componentId, MenuAction.WIDGET_TARGET_ON_WIDGET, 0,
                targetItemId);
    }

    /**
     * Uses an item on a menu entry's subject, as Use on the item and a click on the subject do: the item is selected
     * and the targeted op is run on the subject, in one batch. The entry can be the subject's targeted entry (as the
     * menu shows it while an item is selected) or any of its plain ops; only its subject matters.
     *
     * @param componentId the packed id of the component holding the item (an inventory)
     * @param slot        the item's slot
     * @param itemId      the item's id
     * @param target      the entry of the NPC, loc, ground item, player or component to use the item on
     * @return true if both were sent; false if selecting or the targeted op isn't supported, the hook isn't connected
     * or the entry has no world tile its subject kind needs
     */
    public synchronized boolean useItemOn(int componentId, int slot, int itemId, MenuEntry target) {
        MenuAction targeted = targeted(target.getType());
        if (targeted == null) {
            return false;
        }
        return useItemOn(componentId, slot, itemId, target.getParam0(), target.getParam1(), targeted,
                target.getIdentifier(), target.getItemId(), target.getWorldLocation());
    }

    /**
     * Uses an item on a subject given by its action parameters, as {@link #useItemOn(int, int, int, MenuEntry)} with
     * the parameters of {@link #menuAction(int, int, MenuAction, int, int)}.
     *
     * @param componentId  the packed id of the component holding the item
     * @param slot         the item's slot
     * @param itemId       the item's id
     * @param param0       the subject's first parameter
     * @param param1       the subject's second parameter
     * @param action       the subject's action, targeted or plain
     * @param identifier   the subject's identifier
     * @param targetItemId the item of a component subject, or -1
     * @return true if both were sent
     */
    public synchronized boolean useItemOn(int componentId, int slot, int itemId, int param0, int param1,
                                          MenuAction action, int identifier, int targetItemId) {
        MenuAction targeted = targeted(action);
        return targeted != null
                && useItemOn(componentId, slot, itemId, param0, param1, targeted, identifier, targetItemId, null);
    }

    private boolean useItemOn(int componentId, int slot, int itemId, int param0, int param1, MenuAction targeted,
                              int identifier, int targetItemId, WorldPoint where) {
        NativeCall select = actionCall(slot, componentId, MenuAction.WIDGET_TARGET, 0, itemId, 0, null);
        NativeCall use = actionCall(param0, param1, targeted, identifier, targetItemId, 0, where);
        return select != null && use != null && send(List.of(select, use));
    }

    /**
     * Casts a spell on a menu entry's subject, as the spell's Cast and a click on the subject do: the spell's
     * component is selected (with no slot and no item, as the spellbook's rows select it) and the targeted op is run
     * on the subject, in one batch. The targeted packet carries the spell's component with -1 for the slot and the
     * item, which is what the client sends for a spell. The entry can be the subject's targeted entry or any of its
     * plain ops; only its subject matters. High Alchemy on an inventory item is the item's component entry; a spell on
     * an NPC its NPC entry.
     *
     * @param spellComponentId the packed id of the spell's component in the spellbook
     * @param target           the entry of the NPC, loc, ground item, player or component to cast the spell on
     * @return true if both were sent; false if selecting or the targeted op isn't supported, the hook isn't connected
     * or the entry has no world tile its subject kind needs
     */
    public synchronized boolean castSpellOn(int spellComponentId, MenuEntry target) {
        MenuAction targeted = targeted(target.getType());
        if (targeted == null) {
            return false;
        }
        return useItemOn(spellComponentId, -1, -1, target.getParam0(), target.getParam1(), targeted,
                target.getIdentifier(), target.getItemId(), target.getWorldLocation());
    }

    /**
     * Casts a spell on a subject given by its action parameters, as {@link #castSpellOn(int, MenuEntry)} with the
     * parameters of {@link #menuAction(int, int, MenuAction, int, int)}: for an inventory item, the slot, the
     * inventory's packed id, {@link MenuAction#WIDGET_TARGET_ON_WIDGET} and the item id; for an NPC, its index and
     * {@link MenuAction#WIDGET_TARGET_ON_NPC}.
     *
     * @param spellComponentId the packed id of the spell's component in the spellbook
     * @param param0           the subject's first parameter
     * @param param1           the subject's second parameter
     * @param action           the subject's action, targeted or plain
     * @param identifier       the subject's identifier
     * @param targetItemId     the item of a component subject, or -1
     * @return true if both were sent
     */
    public synchronized boolean castSpellOn(int spellComponentId, int param0, int param1, MenuAction action,
                                            int identifier, int targetItemId) {
        MenuAction targeted = targeted(action);
        return targeted != null
                && useItemOn(spellComponentId, -1, -1, param0, param1, targeted, identifier, targetItemId, null);
    }

    /**
     * Closes the open modal interface as a close button ({@link MenuAction#WIDGET_CLOSE}) does: the client sends the
     * close packet and closes the interface. The button's row body reads nothing from its row, so no component is
     * needed.
     *
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean closeInterface() {
        return send(actionCall(-1, 0, MenuAction.WIDGET_CLOSE, 0, -1, 0, null));
    }

    /**
     * Answers a count dialog ("Enter amount") with the packet the client's scripts send for the number typed.
     *
     * @param count the number
     * @return true if the packet was sent; false if dialogs aren't supported or the hook isn't connected
     */
    public synchronized boolean resumeCountDialog(int count) {
        return send(packetCalls("RESUME_P_COUNTDIALOG", List.of(write("packetWriteInt", count))));
    }

    /**
     * Answers a name dialog with the packet the client's scripts send for the name typed.
     *
     * @param name the name, at most 254 characters
     * @return true if the packet was sent; false if dialogs aren't supported, the hook isn't connected or the text
     * is too long
     */
    public synchronized boolean resumeNameDialog(String name) {
        return send(textCalls("RESUME_P_NAMEDIALOG", name));
    }

    /**
     * Answers a string dialog with the packet the client's scripts send for the text typed.
     *
     * @param text the text, at most 254 characters
     * @return true if the packet was sent; false if dialogs aren't supported, the hook isn't connected or the text
     * is too long
     */
    public synchronized boolean resumeStringDialog(String text) {
        return send(textCalls("RESUME_P_STRINGDIALOG", text));
    }

    /**
     * Answers an item dialog ("Select an item") with the packet the client's scripts send for the item chosen.
     *
     * @param itemId the item's id
     * @return true if the packet was sent; false if dialogs aren't supported or the hook isn't connected
     */
    public synchronized boolean resumeObjDialog(int itemId) {
        return send(packetCalls("RESUME_P_OBJDIALOG", List.of(write("packetWriteShort", itemId & 0xffff))));
    }

    /**
     * Clicks a pause button ("Click here to continue") as its row does: the client sends the pause packet for the
     * component while no answer is pending. Same as {@link MenuAction#WIDGET_CONTINUE} through {@link #menuAction}.
     *
     * @param componentId the packed id of the pause component
     * @param slot        the component's slot, -1 for none
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean resumePause(int componentId, int slot) {
        return send(actionCall(slot, componentId, MenuAction.WIDGET_CONTINUE, 0, -1, 0, null));
    }

    private boolean clickAndRun(int canvasX, int canvasY, NativeCall action) {
        if (action == null) {
            return false;
        }
        List<NativeCall> batch = new ArrayList<>(mouseClickCalls(canvasX, canvasY, false));
        batch.add(action);
        return send(batch);
    }

    /**
     * Uses a new connection to the hook for the calls. Internal: the client's native hook calls this when its
     * connection comes up, and with null when it drops.
     *
     * @param invoker the connection, or null while there is none
     */
    public synchronized void setInvoker(NativeInvoker invoker) {
        this.invoker = invoker;
        this.sendFailed = false;
    }

    private boolean send(NativeCall call) {
        return call != null && send(List.of(call));
    }

    /**
     * Sends calls if the hook is connected. A broken connection is logged once.
     *
     * @param calls the calls
     * @return true if they were sent
     */
    private boolean send(List<NativeCall> calls) {
        if (invoker == null || calls.isEmpty()) {
            return false;
        }
        try {
            invoker.invoke(calls);
            return true;
        } catch (RuntimeException e) {
            if (!sendFailed) {
                sendFailed = true;
                log.log(Level.WARNING, "Could not send an action to the native hook", e);
            }
            return false;
        }
    }

    /**
     * The calls that build and send the mouse click packet, as the network step does for a real click: create the
     * packet in the batch's scratch, write the delay and button, x and y, send it and release its buffer. Marks the
     * click as the last one sent.
     *
     * @param canvasX     the click's x
     * @param canvasY     the click's y
     * @param rightButton true for the right button
     * @return the calls, empty if the mapping lacks what they need
     */
    List<NativeCall> mouseClickCalls(int canvasX, int canvasY, boolean rightButton) {
        if (!isMouseClickSupported()) {
            return List.of();
        }
        long now = System.nanoTime();
        long delay = lastClickNanos == 0 ? MAX_CLICK_DELAY : Math.min(MAX_CLICK_DELAY, (now - lastClickNanos) / 1_000_000);
        lastClickNanos = now;
        int flags = (int) (delay << 1) | (rightButton ? 1 : 0);
        return packetCalls("EVENT_MOUSE_CLICK", List.of(write("packetWriteShort", flags),
                write("packetWriteShort", canvasX & 0xffff), write("packetWriteShort", canvasY & 0xffff)));
    }

    /**
     * The calls that answer a dialog with text: the packet's size byte (the characters plus the terminator) then the
     * characters, which the client's string writer transcodes from UTF-8 to the game's code page and terminates.
     *
     * @param opcode the packet's opcode name
     * @param text   the text
     * @return the calls, empty if dialogs aren't supported or the text can't be sent (too long, or characters
     * outside the basic plane)
     */
    List<NativeCall> textCalls(String opcode, String text) {
        if (!isDialogSupported()) {
            return List.of();
        }
        int chars = text.codePointCount(0, text.length());
        if (chars > MAX_DIALOG_TEXT || text.codePoints().anyMatch(cp -> cp > 0xffff)) {
            return List.of();
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return packetCalls(opcode, List.of(write("packetWriteByte", chars + 1),
                NativeCall.to(functions.get("packetWriteString")).scratch(PACKET_BUFFER).data(utf8).imm(utf8.length)
                        .imm(0).build()));
    }

    /**
     * The calls that build and send one packet: create it in the batch's scratch with the connection's cipher, the
     * writes, send it on the connection and release its buffer.
     *
     * @param opcode the packet's opcode name in {@code enums.clientProt}
     * @param writes the writer calls, each on the packet's buffer
     * @return the calls, empty if the mapping lacks the helpers, a writer or the opcode
     */
    private List<NativeCall> packetCalls(String opcode, List<NativeCall> writes) {
        if (!hasPacketHelpers() || !opcodes.containsKey(opcode) || writes.stream().anyMatch(Objects::isNull)) {
            return List.of();
        }
        List<NativeCall> calls = new ArrayList<>();
        calls.add(NativeCall.to(functions.get("packetCreate")).scratch(PACKET).imm(opcodes.get(opcode))
                .indirect(connection, outCipher).build());
        calls.addAll(writes);
        calls.add(NativeCall.to(functions.get("packetSend")).indirect(connection, 0).scratch(PACKET).build());
        calls.add(NativeCall.to(functions.get("packetRelease")).scratch(PACKET_BUFFER).build());
        return calls;
    }

    /**
     * A writer call on the packet's buffer in the scratch.
     *
     * @param writer the writer's function key
     * @param value  the value to write
     * @return the call, or null if the writer isn't mapped
     */
    private NativeCall write(String writer, long value) {
        Long rva = functions.get(writer);
        return rva == null ? null : NativeCall.to(rva).scratch(PACKET_BUFFER).imm(value).build();
    }

    private NativeCall entryCall(MenuEntry entry) {
        return actionCall(entry.getParam0(), entry.getParam1(), entry.getType(), entry.getIdentifier(),
                entry.getItemId(), entry.getSubOp(), entry.getWorldLocation());
    }

    /**
     * The call that runs an action: the subject kind's row body with the captures its row would hold.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param itemId     a component's item id, or -1 to read it from the widget
     * @param subOp      a component op's sub-option, 1-based, or 0 for none
     * @param where      the subject's world tile, or null to take it from the parameters as scene coordinates
     * @return the call, or null if the action isn't supported, the tile can't be worked out or a component op or
     * sub-option is out of range
     */
    NativeCall actionCall(int param0, int param1, MenuAction action, int identifier, int itemId, int subOp, WorldPoint where) {
        Op op = ops.get(action);
        if (op == null) {
            return null;
        }
        Subject subject = op.subject();
        Long rva = functions.get(subject.function());
        if (rva == null) {
            return null;
        }
        if (subject == Subject.COMPONENT_CLOSE) {
            return NativeCall.to(rva).imm(0).imm(0).build();
        }
        ByteBuffer captures = ByteBuffer.allocate(subject.captureBytes()).order(ByteOrder.LITTLE_ENDIAN);
        switch (subject) {
            case NPC -> captures.putInt(identifier).putInt(op.op()).putInt(0);
            case PLAYER -> captures.putInt(identifier).putInt(op.op());
            case NPC_TARGET, PLAYER_TARGET -> captures.putInt(identifier);
            case LOC, OBJ, LOC_TARGET, OBJ_TARGET, TILE -> {
                WorldPoint tile = where != null ? where : sceneToWorld(param0, param1);
                if (tile == null) {
                    return null;
                }
                captures.putInt(tile.getPlane()).putInt(tile.getX()).putInt(tile.getY());
                if (subject != Subject.TILE) {
                    captures.putInt(identifier);
                }
                if (subject == Subject.OBJ) {
                    captures.putInt(0);
                }
                if (subject == Subject.LOC || subject == Subject.OBJ) {
                    captures.putInt(op.op()).putInt(0);
                }
            }
            case COMPONENT -> {
                if (identifier < 1 || subOp < 0) {
                    return null;
                }
                captures.putInt(param1).putInt(param0).putInt(identifier).putInt(subOp)
                        .putInt(componentItem(param1, param0, itemId)).putInt(0);
            }
            case COMPONENT_TARGET, COMPONENT_SELECT ->
                    captures.putInt(param1).putInt(param0).putInt(componentItem(param1, param0, itemId));
            case COMPONENT_PAUSE -> captures.putInt(param1).putInt(param0);
            case COMPONENT_BUTTON, COMPONENT_TOGGLE, COMPONENT_SET -> captures.putInt(param1);
            case COMPONENT_CLOSE -> {
                return null;
            }
        }
        return NativeCall.to(rva).imm(0).data(captures.array()).build();
    }

    /**
     * The targeted action of a subject's action: what the menu shows for the subject while an item or spell is
     * selected.
     *
     * @param action the subject's action, targeted or plain
     * @return the targeted action, or null for an action without a subject that can be targeted
     */
    private MenuAction targeted(MenuAction action) {
        Op op = ops.get(action);
        if (op == null) {
            return null;
        }
        return switch (op.subject()) {
            case NPC, NPC_TARGET -> MenuAction.WIDGET_TARGET_ON_NPC;
            case PLAYER, PLAYER_TARGET -> MenuAction.WIDGET_TARGET_ON_PLAYER;
            case LOC, LOC_TARGET -> MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
            case OBJ, OBJ_TARGET -> MenuAction.WIDGET_TARGET_ON_GROUND_ITEM;
            case COMPONENT, COMPONENT_TARGET, COMPONENT_SELECT, COMPONENT_PAUSE, COMPONENT_BUTTON, COMPONENT_TOGGLE,
                 COMPONENT_SET, COMPONENT_CLOSE -> MenuAction.WIDGET_TARGET_ON_WIDGET;
            case TILE -> null;
        };
    }

    /**
     * The item a component row captures: the given one, else the item the component's widget (or its child at the
     * slot) holds, else -1.
     *
     * @param componentId the component's packed id
     * @param slot        the slot, -1 for none
     * @param itemId      the item id given, or -1 to read it
     * @return the item id, -1 if there is none
     */
    private int componentItem(int componentId, int slot, int itemId) {
        if (itemId >= 0) {
            return itemId;
        }
        Widget widget = client.getWidget(componentId);
        if (widget != null && slot >= 0) {
            widget = widget.getChild(slot);
        }
        return widget == null ? -1 : widget.getItemId();
    }

    /**
     * The world tile of scene coordinates on the top-level world's plane.
     *
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return the world tile, or null while the map base or plane can't be read
     */
    private WorldPoint sceneToWorld(int sceneX, int sceneY) {
        int plane = client.getTopLevelWorldView().getPlane();
        return plane < 0 ? null : client.toWorld(new MapCoord(plane, sceneX, sceneY));
    }

    /**
     * What a menu action is about, and the row body and captures that kind of row has.
     */
    private enum Subject {

        /**
         * An NPC: {@code {i32 index, i32 op, i32 flag}}.
         */
        NPC("menuNpcOp", 12),

        /**
         * A loc: {@code {i32 level, i32 x, i32 z, i32 locId, i32 op, i32 flag}}.
         */
        LOC("menuLocOp", 24),

        /**
         * A ground item: {@code {i32 level, i32 x, i32 z, i32 objId, i32 count, i32 op, i32 flag}}.
         */
        OBJ("menuObjOp", 28),

        /**
         * A player: {@code {i32 index, i32 op}}.
         */
        PLAYER("menuPlayerOp", 8),

        /**
         * A tile, Walk here: {@code {i32 level, i32 x, i32 z}}.
         */
        TILE("menuWalk", 12),

        /**
         * An NPC a selected item or spell is used on: {@code {i32 index}}.
         */
        NPC_TARGET("menuNpcTargetOp", 4),

        /**
         * A loc a selected item or spell is used on: {@code {i32 level, i32 x, i32 z, i32 locId}}.
         */
        LOC_TARGET("menuLocTargetOp", 16),

        /**
         * A ground item a selected item or spell is used on: {@code {i32 level, i32 x, i32 z, i32 objId}}.
         */
        OBJ_TARGET("menuObjTargetOp", 16),

        /**
         * A player a selected item or spell is used on: {@code {i32 index}}.
         */
        PLAYER_TARGET("menuPlayerTargetOp", 4),

        /**
         * A component's op: {@code {i32 component, i32 slot, i32 op, i32 subOp, i32 objId, u8 flag}}, padded to 24
         * bytes.
         */
        COMPONENT("menuComponentOp", 24),

        /**
         * A component a selected item or spell is used on: {@code {i32 component, i32 slot, i32 objId}}.
         */
        COMPONENT_TARGET("menuComponentTargetOp", 12),

        /**
         * A component's item or spell being selected: {@code {i32 component, i32 slot, i32 objId}}.
         */
        COMPONENT_SELECT("menuComponentSelect", 12),

        /**
         * A pause button: {@code {i32 component, i32 slot}}.
         */
        COMPONENT_PAUSE("menuComponentPause", 8),

        /**
         * An old-style plain button: {@code {i32 component}}.
         */
        COMPONENT_BUTTON("menuComponentButton", 4),

        /**
         * An old-style toggle button: {@code {i32 component}}.
         */
        COMPONENT_TOGGLE("menuComponentToggle", 4),

        /**
         * An old-style set button: {@code {i32 component}}.
         */
        COMPONENT_SET("menuComponentSet", 4),

        /**
         * An old-style close button: no captures, the body reads none.
         */
        COMPONENT_CLOSE("menuComponentClose", 0);

        private final String function;
        private final int captureBytes;

        Subject(String function, int captureBytes) {
            this.function = function;
            this.captureBytes = captureBytes;
        }

        /**
         * The mapping's function key of the row body.
         *
         * @return the key under {@code functions}
         */
        String function() {
            return function;
        }

        /**
         * The size of the captures the body reads.
         *
         * @return the bytes
         */
        int captureBytes() {
            return captureBytes;
        }
    }

    /**
     * One action of one subject kind.
     *
     * @param subject the kind
     * @param op      the op, 1-based, or 0 when the action's identifier is the op or the body has no op
     */
    private record Op(Subject subject, int op) {}
}
