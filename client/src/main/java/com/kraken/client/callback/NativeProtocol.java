package com.kraken.client.callback;

import com.kraken.api.internal.ScriptComponents;
import com.kraken.api.internal.ScriptStack;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.menu.MenuClick;
import com.kraken.api.internal.menu.WireRule;
import com.kraken.api.internal.render.WireVisibility;
import com.kraken.api.internal.widget.ComponentOp;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;
import com.kraken.client.input.KeyHook;
import com.kraken.client.input.KeyManager;
import com.kraken.client.input.KeyReport;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The wire format between {@link NativeEventServer} and {@code kraken_hook.dll} (protocol v7, little-endian). Java
 * sends one handshake naming the raise functions to hook, their event ids and a capture plan per slot, then the
 * functions the drawing needs, then the script runtime's functions and layout, the minimenu's functions and layout,
 * the game window's procedure, where the entities and their visibility flags are, and the interface store's layout;
 * the DLL sends one message per raise with the id, the argument registers and the captured values, one per script
 * event it was asked for, one per click on a menu row of a plugin's own and per key it handled, and status messages
 * with reserved ids. After the handshake Java sends commands: batches of calls for the game thread, the overlay
 * surface to draw, the frame cap, which script events to send, the menu rules, the key remapping, the visibility rules
 * and component writes. Every field and op code here matches the constants in the DLL's {@code src/protocol.h};
 * {@code docs/developer/native-protocol.md} describes the format.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeProtocol {

    /**
     * Handshake magic: identifies protocol v7.
     */
    static final byte[] MAGIC = {'K', 'R', 'K', '7'};

    /**
     * Capture-plan ops: how the DLL reads the argument register a plan entry names.
     */
    static final int OP_RAW = 0;   // the register value itself (u64)
    static final int OP_I32 = 1;   // int32 at [register]
    static final int OP_I64 = 2;   // int64 at [register]
    static final int OP_I32X2 = 3; // two int32 at [register]
    static final int OP_CSTR = 4;  // NUL-terminated bytes at [register]

    /**
     * A reserved event id the DLL sends once after a handshake: hooks installed and requested, features, version.
     */
    static final int STATUS_EVENT_ID = -2;

    /**
     * A reserved event id the DLL sends when the game's framebuffer, GL version or drawn surface changes.
     */
    static final int FRAME_EVENT_ID = -3;

    /**
     * A reserved event id the DLL sends after running a batch of calls: batch id, calls run, calls faulted or
     * skipped, the last return value.
     */
    static final int CALLS_DONE_EVENT_ID = -4;

    /**
     * A reserved event id the DLL sends about once a second: frames the game presented since the last one, how long
     * that took in microseconds, the frame cap in force, and 1 if it counted presents (0: frame steps).
     */
    static final int FPS_EVENT_ID = -5;

    /**
     * A reserved event id for a script event: a script starting or returning, with the top of the stacks (see
     * {@link #parseScript}). It goes through the DLL's ring like a raise.
     */
    static final int SCRIPT_EVENT_ID = -6;

    /**
     * A reserved event id for a click the DLL reports: on a row of a plugin's own, which ran nothing in the client,
     * or on a client row a rule asked about ({@link #parseMenuClick}).
     */
    static final int MENU_CLICK_EVENT_ID = -7;

    /**
     * A reserved event id the DLL sends when a scoped menu rule expired: the rule id in RCX.
     */
    static final int MENU_EXPIRED_EVENT_ID = -8;

    /**
     * A reserved event id for a key message the DLL handled ({@link #parseKey}).
     */
    static final int KEY_EVENT_ID = -9;

    /**
     * The STATUS feature bit for the game-thread pump: the DLL runs calls.
     */
    static final long FEATURE_CALLS = 1;

    /**
     * The STATUS feature bit for the present hook: the DLL draws the overlay surface.
     */
    static final long FEATURE_OVERLAY = 2;

    /**
     * The STATUS feature bit for frame pacing: the DLL sends {@link #FPS_EVENT_ID} and, with the pump, holds the game
     * to the cap {@link #COMMAND_FRAME_LIMIT} sets.
     */
    static final long FEATURE_FRAME_RATE = 4;

    /**
     * The STATUS feature bit for the script hooks: the DLL sends script events while {@link #COMMAND_SCRIPTS} asks.
     */
    static final long FEATURE_SCRIPTS = 8;

    /**
     * The STATUS feature bit for the minimenu hooks: the DLL applies {@link #COMMAND_MENU} rules.
     */
    static final long FEATURE_MENU = 16;

    /**
     * The STATUS feature bit for the window procedure hook: the DLL applies {@link #COMMAND_KEYS} remapping.
     */
    static final long FEATURE_KEYS = 32;

    /**
     * STATUS feature bit: the pump is hooked and the handshake's visibility block was complete, so visibility rules
     * are applied.
     */
    static final long FEATURE_VISIBILITY = 64;

    /**
     * STATUS feature bit: the pump is hooked and the handshake's component block was complete, so component writes
     * are made.
     */
    static final long FEATURE_COMPONENTS = 128;

    /**
     * Command type: a batch of calls.
     */
    static final int COMMAND_CALLS = 1;

    /**
     * Command type: the overlay surface to draw.
     */
    static final int COMMAND_SURFACE = 2;

    /**
     * Command type: the frame rate to hold the game to.
     */
    static final int COMMAND_FRAME_LIMIT = 3;

    /**
     * Command type: which script events to send.
     */
    static final int COMMAND_SCRIPTS = 4;

    /**
     * Command type: the menu rules.
     */
    static final int COMMAND_MENU = 5;

    /**
     * Command type: the key remapping.
     */
    static final int COMMAND_KEYS = 6;

    /**
     * Command type: the visibility rules, replacing the ones the DLL holds.
     */
    static final int COMMAND_VISIBILITY = 7;

    /**
     * Command type: a batch of component writes.
     */
    static final int COMMAND_COMPONENTS = 8;

    /**
     * A layout offset the mapping lacks, which leaves that part off in the DLL.
     */
    static final int NO_OFFSET = 0xffffffff;

    /**
     * The MENU command's operations: replace every rule, add or replace rules by id, remove rules by id.
     */
    static final int MENU_REPLACE = 0;
    static final int MENU_UPSERT = 1;
    static final int MENU_REMOVE = 2;

    /**
     * The KEYS command's operations: the mode table, the mode in force, whether to report keys.
     */
    static final int KEYS_TABLE = 0;
    static final int KEYS_MODE = 1;
    static final int KEYS_REPORT = 2;

    /**
     * Menu rule types.
     */
    static final int RULE_EDIT = 1;
    static final int RULE_SWAP = 2;
    static final int RULE_ADD = 3;

    /**
     * Menu rule flags.
     */
    static final int RULE_SHIFT_DOWN = 1;
    static final int RULE_SHIFT_UP = 2;
    static final int RULE_SCOPED = 4;
    static final int RULE_FINAL = 8;

    /**
     * Match field bits.
     */
    static final int MATCH_KIND = 1;
    static final int MATCH_TYPE_ID = 2;
    static final int MATCH_INDEX = 4;
    static final int MATCH_OP = 8;
    static final int MATCH_TILE = 16;
    static final int MATCH_OPTION = 32;
    static final int MATCH_TARGET = 64;
    static final int MATCH_SUB_ROWS = 128;
    static final int MATCH_GROUP = 256;

    /**
     * Text match modes, {@link TextMatch.Mode}'s ordinals plus one; 0 is no pattern.
     */
    static final int TEXT_ANY = 0;

    /**
     * Edit actions.
     */
    static final int EDIT_HIDE = 1;
    static final int EDIT_DEPRIORITIZE = 2;
    static final int EDIT_PROMOTE = 4;
    static final int EDIT_PROMOTE_SUBJECT = 8;
    static final int EDIT_SET_OPTION = 16;
    static final int EDIT_SET_TARGET = 32;
    static final int EDIT_REPORT = 64;

    /**
     * Swap flags.
     */
    static final int SWAP_SAME_TARGET = 1;
    static final int SWAP_SAME_SUBJECT = 2;
    static final int SWAP_BELOW = 4;
    static final int SWAP_TO_TOP = 8;

    /**
     * The longest text a rule writes into a row.
     */
    static final int MAX_RULE_TEXT = 256;

    /**
     * The mode flag that drops typed characters.
     */
    static final int KEY_MODE_BLOCK_TYPING = 1;

    /**
     * The window messages a KEY report names.
     */
    static final int WM_KEYDOWN = 0x100;
    static final int WM_KEYUP = 0x101;
    static final int WM_CHAR = 0x102;
    static final int WM_SYSKEYDOWN = 0x104;
    static final int WM_SYSKEYUP = 0x105;

    /**
     * The five minimenu functions the DLL hooks or calls, in order.
     */
    private static final String[] MENU_FUNCTIONS = {"minimenuViewRebuild", "minimenuRun", "minimenuNodeDestroy",
            "minimenuNodeReserve", "minimenuTextAssign"};

    /**
     * The menu block's offsets, in the order the DLL reads them: the minimenu behind the client, its rows, open view
     * and last-run copy, the node and list layout, and the entry layout.
     */
    private static final String[] MENU_OFFSETS = {"Client.minimenu", "Minimenu.rows", "Minimenu.open",
            "Minimenu.clicked", "container:minimenu.node.list", "container:minimenu.node.index",
            "container:minimenu.node.children", "container:minimenu.node.size", "container:minimenu.list.entries",
            "MinimenuEntry.size", "MinimenuEntry.option", "MinimenuEntry.target", "MinimenuEntry.typeId",
            "MinimenuEntry.subjectIndex", "MinimenuEntry.opIndex", "MinimenuEntry.level", "MinimenuEntry.x",
            "MinimenuEntry.z", "MinimenuEntry.subjectType"};

    /**
     * The subject kinds the menu block names, in order, from {@code enums.minimenuSubject}.
     */
    private static final String[] MENU_KINDS = {"TILE", "NPC", "LOC", "OBJ", "PLAYER", "COMPONENT"};

    /**
     * The op values the menu block names, in order, from {@code enums.minimenuOp}.
     */
    private static final String[] MENU_OPS = {"EXAMINE", "TARGET"};

    /**
     * The eastl string layout the menu block ends with.
     */
    private static final String[] MENU_STRING = {"container:string.heapFlagByte", "container:string.heapFlagMask",
            "container:string.ssoCapacity"};

    /**
     * The most root scripts a script events command asks for by id; the DLL keeps as many.
     */
    static final int MAX_SCRIPT_ROOTS = 8;

    /**
     * How a script message encodes a value: a kind byte, then an int32, a u16 length and UTF-8 bytes, an int64, or
     * nothing.
     */
    static final int VALUE_OTHER = 0;
    static final int VALUE_INT = 1;
    static final int VALUE_STRING = 2;
    static final int VALUE_LONG = 3;

    /**
     * How many entries the script engine's stacks hold; a reported size beyond it is a bad read.
     */
    static final int MAX_STACK = 1000;

    /**
     * The mapping keys the handshake's script block is built from, in the order the DLL reads them (after the four
     * functions and the runner): the runner's stacks and frame count, a frame's script slot, a shared_ptr's pointer,
     * a script's id and the VarValue's size and kind byte.
     */
    private static final String[] SCRIPT_OFFSETS = {"ScriptRunner.intStack", "ScriptRunner.intStackSize",
            "ScriptRunner.objStack", "ScriptRunner.objStackSize", "ScriptRunner.frameCount", "ScriptFrame.script",
            "container:shared_ptr.ptr", "ClientScript.id", "VarValue.size", "VarValue.type"};

    /**
     * The script block's request fields, a component's id and index, and the eastl string layout, in order.
     */
    private static final String[] SCRIPT_FIELDS = {"HookReq.argCount", "HookReq.args", "HookReq.source",
            "HookReq.target", "HookReq.op", "HookReq.subOp", "HookReq.mouseX", "HookReq.mouseY", "HookReq.keyCode",
            "HookReq.keyChar", "HookReq.opbase", "IfType.packedId", "IfType.subId", "container:string.heapFlagByte",
            "container:string.heapFlagMask", "container:string.ssoCapacity"};

    /**
     * The runner's two components, after the script fields; a key the mapping lacks is sent as {@link #NO_OFFSET}.
     */
    private static final String[] SCRIPT_COMPONENTS = {"ScriptRunner.activeComponent", "ScriptRunner.dotComponent"};

    /**
     * The mapping keys the handshake's visibility block is built from, in the order the DLL reads them: where the
     * worlds, their actors, the projectiles and the graphics objects are, the flags that hide them, and the eastl hash
     * map and list layouts. A key the mapping lacks is sent as {@link #NO_OFFSET}, unless it is one of
     * {@link #VISIBILITY_REQUIRED}.
     */
    private static final String[] VISIBILITY_OFFSETS = {"Client.topLevelWorld", "Client.worlds.map",
            "Client.localPlayerIndex", "World.players", "World.npcs", "World.graphicsObjects", "Client.projectiles",
            "Client.keyedProjectiles", "Actor.hidden", "Actor.render2D", "Projectile.hidden", "KeyedProjectile.hidden",
            "GraphicsObject.hidden", "GraphicsObject.spotAnimId", "container:hash_map.buckets",
            "container:hash_map.bucketCount", "container:hash_map.node.key", "container:hash_map.node.value",
            "container:hash_map.node.next", "container:list.node.next", "container:list.node.value"};

    /**
     * The visibility block's keys without which the DLL can't hide actors, so no block is sent.
     */
    private static final Set<String> VISIBILITY_REQUIRED = Set.of("Client.topLevelWorld", "Client.localPlayerIndex",
            "World.players", "World.npcs", "Actor.hidden", "Actor.render2D", "container:hash_map.buckets",
            "container:hash_map.bucketCount", "container:hash_map.node.key", "container:hash_map.node.value",
            "container:hash_map.node.next");

    /**
     * The mapping keys the handshake's component block is built from, after the listener clear's RVA, in order: the
     * interface store, its group entries and slots, a component's dynamic children, and its ids. All required.
     */
    private static final String[] COMPONENT_OFFSETS = {"Client.interfaceStore", "IfStore.groupCount", "IfStore.groups",
            "IfStoreGroup.stride", "IfStoreGroup.count", "IfStoreGroup.slots", "IfStoreGroup.slotStride",
            "container:shared_ptr.ptr", "IfType.dynCount", "IfType.dynSlots", "IfType.packedId", "IfType.subId"};

    /**
     * The four script functions the DLL hooks, in order.
     */
    private static final String[] SCRIPT_FUNCTIONS = {"executeScript", "executeScriptInner", "scriptLookup",
            "scriptSwapLocals"};

    /**
     * The VarValue kinds the script block names, in order; a kind the mapping lacks is sent as 0xff.
     */
    private static final String[] SCRIPT_KINDS = {"INT", "STRING", "LONG"};

    /**
     * One capture-plan entry: read argument register {@code arg} ({@link NativeEvent#RCX}…{@link NativeEvent#R9})
     * with op {@code op}.
     *
     * @param arg the argument register to read
     * @param op  how to read it (one of the {@code OP_*} ops)
     */
    record Plan(int arg, int op) {}

    /**
     * One raise function to hook: where it is and how the DLL should read the event id and payload from it.
     *
     * @param rva     the raiser's RVA (module-base-relative)
     * @param fixedId the event id this raiser always carries, or -1 to read it from {@code idArg}
     * @param idArg   the argument register holding the id when {@code fixedId} is -1
     * @param args    how many argument registers to forward (0..4)
     * @param plan    the capture plan: which registers to read and how
     */
    record Slot(long rva, int fixedId, int idArg, int args, List<Plan> plan) {}

    /**
     * The functions the drawing needs, which the handshake names after the slots. A zero RVA leaves that part off.
     *
     * @param clientGlobal the RVA of the {@code Client*} slot ({@code globals.client})
     * @param pumpFn       the per-frame step on the game thread ({@code functions.appUpdate}), where calls run
     * @param presentFn    the frame's present ({@code functions.present}), where the overlay is drawn
     */
    record Drawing(long clientGlobal, long pumpFn, long presentFn) {

        /**
         * No calls and no overlay.
         */
        static final Drawing NONE = new Drawing(0, 0, 0);
    }

    /**
     * Builds the handshake: magic, a slot count, then each slot's rva, id configuration, argument count and plan, then
     * the drawing functions, then five length-prefixed blocks (a u16 length each): the script block, the menu block,
     * the key block, the visibility block and the component block.
     *
     * @param slots      the slots to install
     * @param drawing    the client pointer, the pump and the present function
     * @param scripts    the script block ({@link #scriptLayout}), empty for no script hooks
     * @param menu       the menu block ({@link #menuLayout}), empty for no menu hooks
     * @param keys       the key block ({@link #keyLayout}), empty for no key hook
     * @param visibility the visibility block ({@link #visibilityLayout}), empty for no entity hiding
     * @param components the component block ({@link #componentLayout}), empty for no component writes
     * @return the handshake bytes
     */
    static byte[] handshake(List<Slot> slots, Drawing drawing, byte[] scripts, byte[] menu, byte[] keys,
                            byte[] visibility, byte[] components) {
        int size = MAGIC.length + 2 + 3 * 8 + 2 + scripts.length + 2 + menu.length + 2 + keys.length + 2
                + visibility.length + 2 + components.length;
        for (Slot slot : slots) {
            size += 8 + 4 + 1 + 1 + 1 + slot.plan().size() * 2;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putShort((short) slots.size());
        for (Slot slot : slots) {
            buf.putLong(slot.rva());
            buf.putInt(slot.fixedId());
            buf.put((byte) slot.idArg());
            buf.put((byte) slot.args());
            buf.put((byte) slot.plan().size());
            for (Plan plan : slot.plan()) {
                buf.put((byte) plan.arg());
                buf.put((byte) plan.op());
            }
        }
        buf.putLong(drawing.clientGlobal());
        buf.putLong(drawing.pumpFn());
        buf.putLong(drawing.presentFn());
        buf.putShort((short) scripts.length);
        buf.put(scripts);
        buf.putShort((short) menu.length);
        buf.put(menu);
        buf.putShort((short) keys.length);
        buf.put(keys);
        buf.putShort((short) visibility.length);
        buf.put(visibility);
        buf.putShort((short) components.length);
        buf.put(components);
        return buf.array();
    }

    /**
     * Builds the handshake's menu block from a mapping: the RVAs of the five minimenu functions ({@code u64} each),
     * the offsets of {@link #MENU_OFFSETS} ({@code u32} each), the subject kinds of {@link #MENU_KINDS} ({@code u8}
     * each), the op values of {@link #MENU_OPS} ({@code i32} each), then the eastl string layout ({@code u32} each).
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks a key it needs (no menu hooks)
     */
    static byte[] menuLayout(Mapping mapping) {
        for (String function : MENU_FUNCTIONS) {
            if (!mapping.hasFunction(function)) {
                return new byte[0];
            }
        }
        int[] offsets = new int[MENU_OFFSETS.length];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = layoutValue(mapping, MENU_OFFSETS[i]);
        }
        int[] strings = new int[MENU_STRING.length];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = layoutValue(mapping, MENU_STRING[i]);
        }
        int[] kinds = new int[MENU_KINDS.length];
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = mapping.enumValue("minimenuSubject", MENU_KINDS[i], -1);
        }
        int[] ops = new int[MENU_OPS.length];
        for (int i = 0; i < ops.length; i++) {
            if (mapping.enumTable("minimenuOp").get(MENU_OPS[i]) == null) {
                return new byte[0];
            }
            ops[i] = mapping.enumValue("minimenuOp", MENU_OPS[i], 0);
        }
        if (Arrays.stream(offsets).anyMatch(v -> v < 0) || Arrays.stream(strings).anyMatch(v -> v < 0)
                || Arrays.stream(kinds).anyMatch(v -> v < 0)) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(MENU_FUNCTIONS.length * 8 + offsets.length * 4 + kinds.length
                + ops.length * 4 + strings.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (String function : MENU_FUNCTIONS) {
            buf.putLong(mapping.function(function));
        }
        for (int offset : offsets) {
            buf.putInt(offset);
        }
        for (int kind : kinds) {
            buf.put((byte) kind);
        }
        for (int op : ops) {
            buf.putInt(op);
        }
        for (int value : strings) {
            buf.putInt(value);
        }
        return buf.array();
    }

    /**
     * Builds the handshake's key block from a mapping: the RVA of the game window's procedure ({@code u64}).
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks the window procedure (no key hook)
     */
    static byte[] keyLayout(Mapping mapping) {
        if (!mapping.hasFunction("renderViewWndProc")) {
            return new byte[0];
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(mapping.function("renderViewWndProc"))
                .array();
    }

    /**
     * Builds the handshake's visibility block from a mapping: the offsets of {@link #VISIBILITY_OFFSETS} ({@code u32}
     * each), {@link #NO_OFFSET} for an optional one the mapping lacks.
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks a required key (no entity hiding)
     */
    static byte[] visibilityLayout(Mapping mapping) {
        ByteBuffer buf = ByteBuffer.allocate(VISIBILITY_OFFSETS.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (String key : VISIBILITY_OFFSETS) {
            int value = layoutValue(mapping, key);
            if (value < 0 && VISIBILITY_REQUIRED.contains(key)) {
                return new byte[0];
            }
            buf.putInt(value < 0 ? NO_OFFSET : value);
        }
        return buf.array();
    }

    /**
     * Builds the handshake's component block from a mapping: the RVA of the client's listener clear
     * ({@code functions.ifListenerClear}, {@code u64}, 0 if the mapping lacks it, which leaves listeners alone), then
     * the offsets of {@link #COMPONENT_OFFSETS} ({@code u32} each).
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks an offset (no component writes)
     */
    static byte[] componentLayout(Mapping mapping) {
        ByteBuffer buf = ByteBuffer.allocate(8 + COMPONENT_OFFSETS.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(mapping.hasFunction("ifListenerClear") ? mapping.function("ifListenerClear") : 0);
        for (String key : COMPONENT_OFFSETS) {
            int value = layoutValue(mapping, key);
            if (value < 0) {
                return new byte[0];
            }
            buf.putInt(value);
        }
        return buf.array();
    }

    /**
     * Frames the visibility rules command: {@code {u32 len, u8 7, u8 classes, u16 count, count x {u64 address,
     * u8 decided, u8 hidden}, u8 graphics, graphics x i32}}. The length counts the bytes after it.
     *
     * @param rules the rules
     * @return the framed command
     */
    static byte[] visibility(WireVisibility rules) {
        int[] graphics = rules.graphics();
        if (rules.actors().size() > 0xffff || graphics.length > 0xff) {
            throw new IllegalArgumentException("too many actors or graphics objects: " + rules.actors().size() + ", "
                    + graphics.length);
        }
        int body = 1 + 1 + 2 + rules.actors().size() * 10 + 1 + graphics.length * 4;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_VISIBILITY);
        buf.put((byte) rules.classes());
        buf.putShort((short) rules.actors().size());
        for (WireVisibility.ActorRule actor : rules.actors()) {
            buf.putLong(actor.address());
            buf.put((byte) actor.decided());
            buf.put((byte) actor.hidden());
        }
        buf.put((byte) graphics.length);
        for (int id : graphics) {
            buf.putInt(id);
        }
        return buf.array();
    }

    /**
     * Frames a batch of component writes: {@code {u32 len, u8 8, u16 count, count x {i32 packedId, i32 subId, u8 op,
     * u16 offset, i32 value}}}, the op being the ordinal of the write's {@link ComponentOp.Op}. The length counts the
     * bytes after it.
     *
     * @param ops the writes, in order
     * @return the framed command
     */
    static byte[] components(List<ComponentOp> ops) {
        if (ops.size() > 0xffff) {
            throw new IllegalArgumentException("at most 65535 component writes a batch, got " + ops.size());
        }
        int body = 1 + 2 + ops.size() * 15;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_COMPONENTS);
        buf.putShort((short) ops.size());
        for (ComponentOp op : ops) {
            buf.putInt(op.packedId());
            buf.putInt(op.subId());
            buf.put((byte) op.op().ordinal());
            buf.putShort((short) op.offset());
            buf.putInt(op.value());
        }
        return buf.array();
    }

    /**
     * Frames a menu rules command: {@code {u32 len, u8 5, u8 op, u16 count, rules}}, each rule
     * {@code {u32 id, u8 type, u8 flags, match, payload}}. A match is {@code {u16 mask, u8 kind, i32 typeId,
     * i32 index, i32 op, i32 group, i32 level, i32 x, i32 z, text option, text target}}, a text
     * {@code {u8 mode, u8 len, bytes}} (mode 0 for none). An edit's payload is {@code {u8 actions, str option,
     * str target}}, a swap's {@code {u8 flags, match swapped}}, an add's {@code {u8 position, row}} with a row
     * {@code {u32 id, str option, str target, u8 children, children}}; a str is {@code {u16 len, bytes}}.
     *
     * @param op    {@link #MENU_REPLACE} or {@link #MENU_UPSERT}
     * @param rules the rules
     * @return the framed command
     */
    static byte[] menuRules(int op, List<WireRule> rules) {
        if (rules.size() > 0xffff) {
            throw new IllegalArgumentException("at most 65535 rules a command, got " + rules.size());
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + rules.size() * 64 + textBytes(rules))
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        buf.put((byte) COMMAND_MENU);
        buf.put((byte) op);
        buf.putShort((short) rules.size());
        for (WireRule rule : rules) {
            putRule(buf, rule);
        }
        return frame(buf);
    }

    /**
     * Frames a menu removal command: {@code {u32 len, u8 5, u8 2, u16 count, count x u32 id}}.
     *
     * @param ids the rule ids to remove
     * @return the framed command
     */
    static byte[] menuRemove(int[] ids) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + ids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        buf.put((byte) COMMAND_MENU);
        buf.put((byte) MENU_REMOVE);
        buf.putShort((short) ids.length);
        for (int id : ids) {
            buf.putInt(id);
        }
        return frame(buf);
    }

    /**
     * Frames the key table command: {@code {u32 len, u8 6, u8 0, u8 modes, modes}}, each mode
     * {@code {u8 flags, u16 count, count x {u16 vk, u8 modifiers, u8 mask, u16 to, u8 next}}}.
     *
     * @param modes the table
     * @return the framed command
     */
    static byte[] keysTable(List<KeyHook.Mode> modes) {
        int size = 4 + 3;
        for (KeyHook.Mode mode : modes) {
            size += 3 + mode.rules().size() * 7;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        buf.put((byte) COMMAND_KEYS);
        buf.put((byte) KEYS_TABLE);
        buf.put((byte) modes.size());
        for (KeyHook.Mode mode : modes) {
            buf.put((byte) (mode.blockTyping() ? KEY_MODE_BLOCK_TYPING : 0));
            buf.putShort((short) mode.rules().size());
            for (KeyHook.Rule rule : mode.rules()) {
                buf.putShort((short) rule.vk());
                buf.put((byte) rule.modifiers());
                buf.put((byte) rule.modifierMask());
                buf.putShort((short) rule.toVk());
                buf.put((byte) rule.nextMode());
            }
        }
        return frame(buf);
    }

    /**
     * Frames the key mode command: {@code {u32 len, u8 6, u8 1, u8 mode}}.
     *
     * @param mode the mode in force
     * @return the framed command
     */
    static byte[] keysMode(int mode) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 3).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        buf.put((byte) COMMAND_KEYS);
        buf.put((byte) KEYS_MODE);
        buf.put((byte) mode);
        return frame(buf);
    }

    /**
     * Frames the key report command: {@code {u32 len, u8 6, u8 2, u8 on}}.
     *
     * @param on whether the DLL reports the keys it handles
     * @return the framed command
     */
    static byte[] keysReport(boolean on) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 3).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(4);
        buf.put((byte) COMMAND_KEYS);
        buf.put((byte) KEYS_REPORT);
        buf.put((byte) (on ? 1 : 0));
        return frame(buf);
    }

    /**
     * Parses a menu click message's registers: the kind (0 a row of a plugin's own, 1 a rule's report), the row or
     * rule id, the parent row id (0xffffffff for none) and the mouse position ({@code x | y << 32}).
     *
     * @param event the message
     * @return the click
     */
    static MenuClick parseMenuClick(NativeEvent event) {
        long parent = event.reg(NativeEvent.R8);
        long mouse = event.reg(NativeEvent.R9);
        return new MenuClick(event.reg(NativeEvent.RCX) == 1, (int) event.reg(NativeEvent.RDX),
                parent == 0xffffffffL ? -1 : (int) parent, (int) mouse, (int) (mouse >>> 32));
    }

    /**
     * Parses a key message's registers: the window message, the key or character it carried, what was done
     * ({@code action << 16 | the key it became}) and the modes ({@code before | after << 8}).
     *
     * @param event the message
     * @return the report, with Java key codes
     */
    static KeyReport parseKey(NativeEvent event) {
        int message = (int) event.reg(NativeEvent.RCX);
        long key = event.reg(NativeEvent.RDX);
        long done = event.reg(NativeEvent.R8);
        long modes = event.reg(NativeEvent.R9);
        KeyReport.Kind kind = switch (message) {
            case WM_KEYDOWN, WM_SYSKEYDOWN -> KeyReport.Kind.PRESSED;
            case WM_KEYUP, WM_SYSKEYUP -> KeyReport.Kind.RELEASED;
            default -> KeyReport.Kind.TYPED;
        };
        KeyReport.Action action = switch ((int) (done >>> 16)) {
            case 1 -> KeyReport.Action.REMAPPED;
            case 2 -> KeyReport.Action.BLOCKED;
            default -> KeyReport.Action.PASSED;
        };
        int to = (int) (done & 0xffff);
        boolean typed = kind == KeyReport.Kind.TYPED;
        return new KeyReport(kind, typed ? 0 : KeyManager.toJava((int) key), typed ? (char) key : 0, action,
                action == KeyReport.Action.REMAPPED ? KeyManager.toJava(to) : 0, (int) (modes & 0xff),
                (int) ((modes >>> 8) & 0xff));
    }

    /**
     * How many bytes the texts of some rules take at most, for sizing a buffer.
     *
     * @param rules the rules
     * @return the bytes
     */
    private static int textBytes(List<WireRule> rules) {
        int bytes = 0;
        for (WireRule rule : rules) {
            bytes += 2 * (TextMatch.MAX_LENGTH + 2) * 2 + 2 * (MAX_RULE_TEXT * 3 + 2);
            if (rule.row() != null) {
                bytes += rowBytes(rule.row());
            }
        }
        return bytes;
    }

    private static int rowBytes(WireRule.Row row) {
        int bytes = 4 + 2 * (MAX_RULE_TEXT * 3 + 2) + 1;
        for (WireRule.Row child : row.children()) {
            bytes += rowBytes(child);
        }
        return bytes;
    }

    /**
     * Writes the frame's length over its first four bytes and trims the buffer to what was written.
     *
     * @param buf the buffer, positioned after the body
     * @return the framed bytes
     */
    private static byte[] frame(ByteBuffer buf) {
        int end = buf.position();
        buf.putInt(0, end - 4);
        return Arrays.copyOf(buf.array(), end);
    }

    private static void putRule(ByteBuffer buf, WireRule rule) {
        buf.putInt(rule.id());
        buf.put((byte) switch (rule.type()) {
            case EDIT -> RULE_EDIT;
            case SWAP -> RULE_SWAP;
            case ADD -> RULE_ADD;
        });
        int flags = (rule.shift() == ShiftState.HELD ? RULE_SHIFT_DOWN : 0)
                | (rule.shift() == ShiftState.NOT_HELD ? RULE_SHIFT_UP : 0) | (rule.scoped() ? RULE_SCOPED : 0)
                | (rule.finalRow() ? RULE_FINAL : 0);
        buf.put((byte) flags);
        putMatch(buf, rule.match());
        switch (rule.type()) {
            case EDIT -> {
                int actions = 0;
                for (MenuRule.Edit.Action action : rule.actions()) {
                    actions |= switch (action) {
                        case HIDE -> EDIT_HIDE;
                        case DEPRIORITIZE -> EDIT_DEPRIORITIZE;
                        case PROMOTE -> EDIT_PROMOTE;
                        case PROMOTE_SUBJECT -> EDIT_PROMOTE_SUBJECT;
                        case SET_OPTION -> EDIT_SET_OPTION;
                        case SET_TARGET -> EDIT_SET_TARGET;
                        case REPORT -> EDIT_REPORT;
                    };
                }
                buf.put((byte) actions);
                putString(buf, rule.option());
                putString(buf, rule.target());
            }
            case SWAP -> {
                int flagsSwap = (rule.sameTarget() ? SWAP_SAME_TARGET : 0) | (rule.sameSubject() ? SWAP_SAME_SUBJECT : 0)
                        | (rule.below() ? SWAP_BELOW : 0) | (rule.toTop() ? SWAP_TO_TOP : 0);
                buf.put((byte) flagsSwap);
                putMatch(buf, rule.swapped());
            }
            case ADD -> {
                buf.put((byte) rule.position().ordinal());
                putRow(buf, rule.row(), true);
            }
        }
    }

    private static void putMatch(ByteBuffer buf, WireRule.Match match) {
        int mask = 0;
        for (WireRule.Match.Field field : match.mask()) {
            mask |= switch (field) {
                case KIND -> MATCH_KIND;
                case TYPE_ID -> MATCH_TYPE_ID;
                case INDEX -> MATCH_INDEX;
                case OP -> MATCH_OP;
                case TILE -> MATCH_TILE;
                case OPTION -> MATCH_OPTION;
                case TARGET -> MATCH_TARGET;
                case GROUP -> MATCH_GROUP;
            };
        }
        if (match.subRows()) {
            mask |= MATCH_SUB_ROWS;
        }
        buf.putShort((short) mask);
        buf.put((byte) match.kind());
        buf.putInt(match.typeId());
        buf.putInt(match.index());
        buf.putInt(match.op());
        buf.putInt(match.group());
        buf.putInt(match.level());
        buf.putInt(match.x());
        buf.putInt(match.z());
        putText(buf, match.option());
        putText(buf, match.target());
    }

    private static void putText(ByteBuffer buf, TextMatch text) {
        if (text == null) {
            buf.put((byte) TEXT_ANY);
            buf.put((byte) 0);
            return;
        }
        byte[] bytes = text.text().getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, TextMatch.MAX_LENGTH);
        buf.put((byte) (text.mode().ordinal() + 1));
        buf.put((byte) n);
        buf.put(bytes, 0, n);
    }

    private static void putString(ByteBuffer buf, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, MAX_RULE_TEXT);
        buf.putShort((short) n);
        buf.put(bytes, 0, n);
    }

    private static void putRow(ByteBuffer buf, WireRule.Row row, boolean children) {
        buf.putInt(row.id());
        putString(buf, row.option());
        putString(buf, row.target());
        List<WireRule.Row> subRows = children ? row.children() : List.of();
        buf.put((byte) subRows.size());
        for (WireRule.Row child : subRows) {
            putRow(buf, child, false);
        }
    }

    /**
     * Builds the handshake's script block from a mapping: the RVAs of the four script functions and of the runner
     * ({@code u64} each), the offsets of {@link #SCRIPT_OFFSETS} ({@code u32} each), the VarValue kinds INT, STRING
     * and LONG ({@code u8} each), then the offsets of {@link #SCRIPT_FIELDS} ({@code u32} each), then those of
     * {@link #SCRIPT_COMPONENTS} ({@code u32} each, {@link #NO_OFFSET} for one the mapping lacks).
     *
     * @param mapping the attached build's mapping
     * @return the block, or an empty array if the mapping lacks a key it needs (no script hooks)
     */
    static byte[] scriptLayout(Mapping mapping) {
        if (!mapping.hasGlobal("scriptRunner")) {
            return new byte[0];
        }
        for (String function : SCRIPT_FUNCTIONS) {
            if (!mapping.hasFunction(function)) {
                return new byte[0];
            }
        }
        int[] offsets = new int[SCRIPT_OFFSETS.length];
        int[] fields = new int[SCRIPT_FIELDS.length];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = layoutValue(mapping, SCRIPT_OFFSETS[i]);
        }
        for (int i = 0; i < fields.length; i++) {
            fields[i] = layoutValue(mapping, SCRIPT_FIELDS[i]);
        }
        if (Arrays.stream(offsets).anyMatch(v -> v < 0) || Arrays.stream(fields).anyMatch(v -> v < 0)) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate((SCRIPT_FUNCTIONS.length + 1) * 8 + offsets.length * 4
                + SCRIPT_KINDS.length + fields.length * 4 + SCRIPT_COMPONENTS.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (String function : SCRIPT_FUNCTIONS) {
            buf.putLong(mapping.function(function));
        }
        buf.putLong(mapping.global("scriptRunner"));
        for (int offset : offsets) {
            buf.putInt(offset);
        }
        for (String kind : SCRIPT_KINDS) {
            buf.put((byte) mapping.enumValue("varValueType", kind, 0xff));
        }
        for (int field : fields) {
            buf.putInt(field);
        }
        for (String key : SCRIPT_COMPONENTS) {
            int value = layoutValue(mapping, key);
            buf.putInt(value < 0 ? NO_OFFSET : value);
        }
        return buf.array();
    }

    /**
     * One value of the script block: a struct field, or a container layout value for a {@code container:} key.
     *
     * @param mapping the mapping
     * @param key     the key
     * @return the value, or -1 if the mapping lacks it
     */
    private static int layoutValue(Mapping mapping, String key) {
        if (key.startsWith("container:")) {
            try {
                return mapping.container(key.substring("container:".length()));
            } catch (RuntimeException e) {
                return -1;
            }
        }
        return mapping.offsetOr(key, -1);
    }

    /**
     * Frames the script events command: {@code {u32 len, u8 4, u8 flags, u8 count, count x i32 id}}, bit 0 asking for
     * scripts starting, bit 1 for scripts returning and bit 2 for the listed root scripts starting (with bit 0 off, only
     * those). At most {@link #MAX_SCRIPT_ROOTS} ids are sent. The length counts the bytes after it.
     *
     * @param pre   whether to send scripts starting
     * @param post  whether to send scripts returning
     * @param roots root scripts to send as they start, by id
     * @return the framed command
     */
    static byte[] scriptCapture(boolean pre, boolean post, int[] roots) {
        int count = Math.min(roots.length, MAX_SCRIPT_ROOTS);
        int body = 1 + 1 + 1 + 4 * count;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_SCRIPTS);
        buf.put((byte) ((pre ? 1 : 0) | (post ? 2 : 0) | (count > 0 ? 4 : 0)));
        buf.put((byte) count);
        for (int i = 0; i < count; i++) {
            buf.putInt(roots[i]);
        }
        return buf.array();
    }

    /**
     * Frames a batch of calls: {@code {u32 len, u8 1, u32 batch, u16 count, calls}}, each call
     * {@code {u64 rva, u8 argc, argc x {u8 kind, i64 value}, u16 dataLen, data}}, the kind being the ordinal of the
     * argument's {@link NativeCall.Kind}. The length counts the bytes after it.
     *
     * @param batch the batch id the DLL reports back when the batch has run
     * @param calls the calls, in order
     * @return the framed command
     */
    static byte[] calls(int batch, List<NativeCall> calls) {
        if (calls.size() > 0xffff) {
            throw new IllegalArgumentException("at most 65535 calls a batch, got " + calls.size());
        }
        int body = 1 + 4 + 2;
        for (NativeCall call : calls) {
            body += 8 + 1 + call.args().size() * 9 + 2 + call.data().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_CALLS);
        buf.putInt(batch);
        buf.putShort((short) calls.size());
        for (NativeCall call : calls) {
            buf.putLong(call.rva());
            buf.put((byte) call.args().size());
            for (NativeCall.Arg arg : call.args()) {
                buf.put((byte) arg.kind().ordinal());
                buf.putLong(arg.value());
            }
            byte[] data = call.data();
            buf.putShort((short) data.length);
            buf.put(data);
        }
        return buf.array();
    }

    /**
     * Frames the overlay surface command: {@code {u32 len, u8 2, u32 generation, u16 nameLen, name}}. An empty name
     * stops the drawing. The length counts the bytes after it.
     *
     * @param generation the surface's generation
     * @param name       the file mapping's name (ASCII), or "" to stop
     * @return the framed command
     */
    static byte[] surface(int generation, String name) {
        byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
        int body = 1 + 4 + 2 + ascii.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_SURFACE);
        buf.putInt(generation);
        buf.putShort((short) ascii.length);
        buf.put(ascii);
        return buf.array();
    }

    /**
     * Frames the frame cap command: {@code {u32 len, u8 3, u32 fps}}, 0 for no cap. The length counts the bytes after
     * it.
     *
     * @param fps the frames a second to hold the game to, or 0 to let it run free
     * @return the framed command
     */
    static byte[] frameLimit(int fps) {
        int body = 1 + 4;
        ByteBuffer buf = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(body);
        buf.put((byte) COMMAND_FRAME_LIMIT);
        buf.putInt(Math.max(0, fps));
        return buf.array();
    }

    /**
     * A message body's event id, without parsing the rest.
     *
     * @param body the message body
     * @return the event or reserved id
     */
    static int eventId(byte[] body) {
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
    }

    /**
     * A message body's sequence number, without parsing the rest.
     *
     * @param body the message body
     * @return the sequence number
     */
    static long seq(byte[] body) {
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xffffffffL;
    }

    /**
     * Parses a script event's body: the usual head, whose registers are {@code {kind, script id, depth, 0}} (kind 0 a
     * root script starting, 1 a gosub, 2 a return), no plan values, then the int stack's size, a u16 count and that
     * many ints from the top of the stack (oldest first), the object stack's size and a run of values from its top,
     * then the engine's active and dot components (a packed id and an index each, {@code i32}s, -1 for none), then a
     * byte saying whether a request follows: the source and target components' ids and indices, the op, sub-op,
     * mouse x and y, key code and key char ({@code i32} each), the base op name (a u16 length and UTF-8 bytes) and a
     * run of values, the listener's arguments. A run of values is a u16 count, then each a kind byte
     * ({@link #VALUE_INT}…) and its payload. The stacks come back as long as their sizes, the entries below the
     * captured top left 0 or null.
     *
     * @param body the message body
     * @return the script event
     */
    static ScriptRun parseScript(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8);
        int kind = (int) buf.getLong();
        int scriptId = (int) buf.getLong();
        int depth = (int) buf.getLong();
        buf.getLong();
        buf.get();

        int intSize = buf.getInt();
        int[] top = new int[buf.getShort() & 0xffff];
        for (int i = 0; i < top.length; i++) {
            top[i] = buf.getInt();
        }
        int[] ints = new int[stackLength(intSize, top.length)];
        System.arraycopy(top, 0, ints, ints.length - top.length, top.length);

        int objectSize = buf.getInt();
        Object[] topObjects = values(buf);
        Object[] objects = new Object[stackLength(objectSize, topObjects.length)];
        System.arraycopy(topObjects, 0, objects, objects.length - topObjects.length, topObjects.length);

        ScriptComponents components = new ScriptComponents(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());

        ScriptRequest request = null;
        if (buf.get() != 0) {
            int[] fields = new int[10];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = buf.getInt();
            }
            String opbase = string(buf);
            Object[] arguments = values(buf);
            request = new ScriptRequest(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                    fields[7], fields[8], fields[9], opbase, arguments);
        }
        ScriptRun.Kind runKind = switch (kind) {
            case 0 -> ScriptRun.Kind.PRE_ROOT;
            case 1 -> ScriptRun.Kind.PRE_GOSUB;
            default -> ScriptRun.Kind.POST;
        };
        return new ScriptRun(runKind, scriptId, depth, new ScriptStack(ints, objects), request, components);
    }

    /**
     * How long a captured stack's array is: its reported size, unless that is a bad read or less than what came.
     *
     * @param size     the size the DLL read
     * @param captured how many entries it sent
     * @return the array length
     */
    private static int stackLength(int size, int captured) {
        return size >= captured && size <= MAX_STACK ? size : captured;
    }

    /**
     * Reads a run of values: a u16 count, then each a kind byte and its payload.
     *
     * @param buf the message, at the run
     * @return the values: {@code Integer}s, {@code String}s and {@code Long}s, null for other kinds
     */
    private static Object[] values(ByteBuffer buf) {
        Object[] values = new Object[buf.getShort() & 0xffff];
        for (int i = 0; i < values.length; i++) {
            values[i] = switch (buf.get()) {
                case VALUE_INT -> buf.getInt();
                case VALUE_STRING -> string(buf);
                case VALUE_LONG -> buf.getLong();
                default -> null;
            };
        }
        return values;
    }

    /**
     * Reads a u16 length and that many UTF-8 bytes.
     *
     * @param buf the message, at the string
     * @return the string
     */
    private static String string(ByteBuffer buf) {
        byte[] bytes = new byte[buf.getShort() & 0xffff];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Parses one message body (the bytes after the 4-byte length frame) into a {@link NativeEvent}.
     *
     * @param body     the message body
     * @param idToName the event id to its name (null for an id the mapping doesn't know)
     * @return the parsed event; its {@link NativeEvent#name()} is null for an unknown id
     */
    static NativeEvent parse(byte[] body, IntFunction<String> idToName) {
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        long seq = buf.getInt() & 0xffffffffL;
        int eventId = buf.getInt();
        long[] regs = new long[4];
        for (int i = 0; i < regs.length; i++) {
            regs[i] = buf.getLong();
        }
        int nValues = buf.get() & 0xff;
        Map<Integer, Object> values = new HashMap<>();
        for (int i = 0; i < nValues; i++) {
            int arg = buf.get() & 0xff;
            int op = buf.get() & 0xff;
            Object value = switch (op) {
                case OP_RAW, OP_I64 -> buf.getLong();
                case OP_I32 -> buf.getInt();
                case OP_I32X2 -> new int[]{buf.getInt(), buf.getInt()};
                case OP_CSTR -> {
                    int length = buf.get() & 0xff;
                    byte[] bytes = new byte[length];
                    buf.get(bytes);
                    yield new String(bytes, StandardCharsets.US_ASCII);
                }
                default -> null;
            };
            if (value != null) {
                values.put(arg, value);
            }
        }
        return new NativeEvent(idToName.apply(eventId), eventId, regs, values, seq);
    }
}
