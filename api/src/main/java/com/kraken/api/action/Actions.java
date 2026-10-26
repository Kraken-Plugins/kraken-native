package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.internal.invoke.NativeInvoker;
import com.kraken.api.mapping.Mapping;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * client's own interaction (facing and pathing to the target). A mouse click can go first, as the game's network step
 * sends one for a real click: {@link #click} sends the click packet and the action in one batch, in that order.
 *
 * <p>What is covered follows what the menu builders capture: an NPC's or player's op by index, a loc's or ground
 * item's op by id and world tile ({@link #isSupported}). Examine, Walk here, component ops and entries aimed by a
 * selected item or spell have no mapped body and are not sent. Calls only reach the client while its native hook is
 * connected ({@link #isAvailable()}); actions asked for before that are dropped, not queued, since an action is only
 * meant for the moment it is asked.
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
     * Where the packet struct the click calls build starts in the batch's scratch block, and its buffer inside it.
     */
    private static final int PACKET = 0;
    private static final int PACKET_BUFFER = PACKET + 8;

    private static final int NPC_OPS = 5;
    private static final int LOC_OPS = 5;
    private static final int OBJ_OPS = 5;
    private static final int PLAYER_OPS = 8;

    private final Client client;
    private final Map<String, Long> functions = new HashMap<>();
    private final Map<MenuAction, Op> ops = new EnumMap<>(MenuAction.class);
    private final int connection;
    private final int outCipher;
    private final int mouseClickOpcode;

    private NativeInvoker invoker;
    private boolean sendFailed;
    private long lastClickNanos;

    /**
     * Resolves the row bodies and packet helpers once.
     *
     * @param client  the client, for the map base when an action names scene coordinates
     * @param mapping the client build's mapping
     */
    public Actions(Client client, Mapping mapping) {
        this.client = Objects.requireNonNull(client, "client");
        for (String key : List.of("menuNpcOp", "menuLocOp", "menuObjOp", "menuPlayerOp", "packetCreate",
                "packetWriteShort", "packetSend", "packetRelease")) {
            if (mapping.hasFunction(key)) {
                functions.put(key, mapping.function(key));
            }
        }
        this.connection = mapping.has("Client.connection") ? mapping.offset("Client.connection") : -1;
        this.outCipher = mapping.has("Connection.outCipher") ? mapping.offset("Connection.outCipher") : -1;
        this.mouseClickOpcode = mapping.enumValue("clientProt", "EVENT_MOUSE_CLICK", -1);
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
        return connection >= 0 && outCipher >= 0 && mouseClickOpcode >= 0
                && functions.containsKey("packetCreate") && functions.containsKey("packetWriteShort")
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
     * Runs a menu entry's action as a click on it would, without the mouse click packet.
     *
     * @param entry the entry, from {@link Client#getMenuEntries()} or a menu event
     * @return true if the action was sent; false if it isn't supported, the hook isn't connected or the entry has no
     * world tile its subject kind needs
     */
    public synchronized boolean menuAction(MenuEntry entry) {
        return send(actionCall(entry.getParam0(), entry.getParam1(), entry.getType(), entry.getIdentifier(),
                entry.getWorldLocation()));
    }

    /**
     * Runs a menu action as a click on its entry would, without the mouse click packet. The parameters are the entry's
     * ({@link MenuEntry}): for an NPC or player action the identifier is the index; for a loc or ground item action
     * the identifier is the loc or item id and the parameters are the tile's scene x and y, on the top-level world's
     * plane. A ground item's count, which only the fifth op's case passes on, is sent as 0.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @return true if the action was sent; false if it isn't supported or the hook isn't connected
     */
    public synchronized boolean menuAction(int param0, int param1, MenuAction action, int identifier) {
        return send(actionCall(param0, param1, action, identifier, null));
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
        return clickAndRun(canvasX, canvasY, actionCall(entry.getParam0(), entry.getParam1(), entry.getType(),
                entry.getIdentifier(), entry.getWorldLocation()));
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
        return clickAndRun(canvasX, canvasY, actionCall(param0, param1, action, identifier, null));
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
        long write = functions.get("packetWriteShort");
        return List.of(
                NativeCall.to(functions.get("packetCreate")).scratch(PACKET).imm(mouseClickOpcode)
                        .indirect(connection, outCipher).build(),
                NativeCall.to(write).scratch(PACKET_BUFFER).imm(flags).build(),
                NativeCall.to(write).scratch(PACKET_BUFFER).imm(canvasX & 0xffff).build(),
                NativeCall.to(write).scratch(PACKET_BUFFER).imm(canvasY & 0xffff).build(),
                NativeCall.to(functions.get("packetSend")).indirect(connection, 0).scratch(PACKET).build(),
                NativeCall.to(functions.get("packetRelease")).scratch(PACKET_BUFFER).build());
    }

    /**
     * The call that runs an action: the subject kind's row body with the captures its row would hold.
     *
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param action     the action
     * @param identifier the identifier
     * @param where      the subject's world tile, or null to take it from the parameters as scene coordinates
     * @return the call, or null if the action isn't supported or the tile can't be worked out
     */
    NativeCall actionCall(int param0, int param1, MenuAction action, int identifier, WorldPoint where) {
        Op op = ops.get(action);
        if (op == null) {
            return null;
        }
        Long rva = functions.get(op.subject().function());
        if (rva == null) {
            return null;
        }
        ByteBuffer captures = ByteBuffer.allocate(op.subject().captureBytes()).order(ByteOrder.LITTLE_ENDIAN);
        switch (op.subject()) {
            case NPC -> captures.putInt(identifier).putInt(op.op()).putInt(0);
            case PLAYER -> captures.putInt(identifier).putInt(op.op());
            case LOC, OBJ -> {
                WorldPoint tile = where != null ? where : sceneToWorld(param0, param1);
                if (tile == null) {
                    return null;
                }
                captures.putInt(tile.getPlane()).putInt(tile.getX()).putInt(tile.getY()).putInt(identifier);
                if (op.subject() == Subject.OBJ) {
                    captures.putInt(0);
                }
                captures.putInt(op.op()).putInt(0);
            }
        }
        return NativeCall.to(rva).imm(0).data(captures.array()).build();
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
        PLAYER("menuPlayerOp", 8);

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
     * One op of one subject kind.
     *
     * @param subject the kind
     * @param op      the op, 1-based
     */
    private record Op(Subject subject, int op) {
    }
}
