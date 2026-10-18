package com.kraken.api.game;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.struct.StructReader;

import java.util.ArrayList;
import java.util.List;

/**
 * The read side of the native API: Java methods that mirror the client's Lua {@code ClientOp} /
 * {@code ClientState} getters, reproduced by following the mapped offset chains in the live process.
 *
 * <p>Every chain starts at the global {@code Client} pointer ({@code moduleBase + globals.client}), then
 * reaches the current {@code World} and its player/NPC tables. Reads are tolerant: an unreadable or
 * logged-out state yields empty results (empty arrays, "", null, -1) rather than exceptions.
 *
 * <p>Naming note: player names live behind a pointer at {@code Player+0x718} (the pointed-to object is an
 * EASTL string), whereas an NPC's own name is the inline string at {@code Npc+0x710}. The committed mapping
 * only has the shared {@code Actor.name = 0x710}, so the player path uses {@code Player.name} if the updater
 * ever adds it and otherwise falls back to {@code 0x718}.
 */
public final class Client {

    private static final int GAME_STATE_LOGGED_IN = 30;
    private static final int MAX_PLAYERS = 2048;
    private static final int MAX_NPCS = 65536;

    private final Memory memory;
    private final Mapping mapping;
    private final StructReader structs;

    private final long clientRva;
    private final int cTopWorld;
    private final int cLocalIndex;
    private final int cPlayerCount;
    private final int cPlayerIndices;
    private final int cTick;
    private final int cGameState;

    private final int wPlane;
    private final int wPlayers;
    private final int wNpcs;
    private final int wNpcIndices;
    private final int wNpcCount;

    private final int aPathX;
    private final int aPathZ;
    private final int aNpcName;   // Npc's own inline name string (Actor.name = 0x710)
    private final int pNamePtr;   // Player name pointer (Player+0x718)

    private final int npcType;
    private final int npcTypeId;
    private final int npcTypeName;

    public Client(Memory memory, Mapping mapping) {
        this.memory = memory;
        this.mapping = mapping;
        this.structs = new StructReader(memory, mapping);

        this.clientRva = mapping.global("client");
        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cLocalIndex = mapping.offset("Client.localPlayerIndex");
        this.cPlayerCount = mapping.offset("Client.playerIndexCount");
        this.cPlayerIndices = mapping.offset("Client.playerIndices");
        this.cTick = mapping.offset("Client.tickCount");
        this.cGameState = mapping.offset("Client.gameState");

        this.wPlane = mapping.offset("World.plane");
        this.wPlayers = mapping.offset("World.players");
        this.wNpcs = mapping.offset("World.npcs");
        this.wNpcIndices = mapping.offset("World.npcIndices");
        this.wNpcCount = mapping.offset("World.npcIndexCount");

        this.aPathX = mapping.offset("Actor.pathX");
        this.aPathZ = mapping.offset("Actor.pathZ");
        this.aNpcName = mapping.offset("Actor.name");
        this.pNamePtr = mapping.offsetOr("Player.name", 0x718);

        this.npcType = mapping.offset("Npc.type");
        this.npcTypeId = mapping.offset("NpcType.id");
        this.npcTypeName = mapping.offset("NpcType.name");
    }

    // ---- Client scalars (ClientState.*) --------------------------------------------------------

    /** The raw game-state value ({@code Client.gameState}); 30 means logged in. -1 if unreadable. */
    public int gameState() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cGameState, -1);
    }

    public boolean isLoggedIn() {
        return gameState() == GAME_STATE_LOGGED_IN;
    }

    /** Server tick counter ({@code Client.tickCount}); increases once per game tick. */
    public int getTickCount() {
        long client = clientBase();
        return client == 0 ? 0 : safeInt(client + cTick, 0);
    }

    /** Index of the local player, or -1 when logged out / loading. */
    public int getLocalPlayerIndex() {
        long client = clientBase();
        if (client == 0) {
            return -1;
        }
        return safeInt(client + cLocalIndex, -1);
    }

    /** Mirrors {@code ClientOp.playerFindSelf}: the local player's index, or -1 if there isn't one. */
    public int playerFindSelf() {
        return getLocalPlayerIndex();
    }

    // ---- Players (ClientOp.*) ------------------------------------------------------------------

    /** Indices of every loaded player (the inline index array in the Client). */
    public int[] getPlayerIdAll() {
        long client = clientBase();
        if (client == 0) {
            return new int[0];
        }
        int count = safeInt(client + cPlayerCount, 0);
        return readIndexArray(client + cPlayerIndices, count, MAX_PLAYERS);
    }

    public String playerName(int index) {
        long player = playerAddress(index);
        if (player == 0) {
            return "";
        }
        long nameObject = safePointer(player + pNamePtr);
        return structs.string(nameObject);
    }

    public MapCoord playerCoord(int index) {
        return actorCoord(playerAddress(index));
    }

    public Player getPlayerObj(int index) {
        long player = playerAddress(index);
        if (player == 0) {
            return null;
        }
        return new Player(index, playerName(index), actorCoord(player));
    }

    public Player getLocalPlayer() {
        int index = getLocalPlayerIndex();
        return index < 0 ? null : getPlayerObj(index);
    }

    public List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();
        for (int index : getPlayerIdAll()) {
            Player player = getPlayerObj(index);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    // ---- NPCs (ClientOp.*) ---------------------------------------------------------------------

    /** Indices of every loaded NPC in the current world. */
    public int[] getNpcIdAll() {
        long world = topWorld();
        if (world == 0) {
            return new int[0];
        }
        int count = safeInt(world + wNpcCount, 0);
        long base = safePointer(world + wNpcIndices);
        return readIndexArray(base, count, MAX_NPCS);
    }

    /** NPC name: its own inline name if set, otherwise its definition's name. */
    public String npcName(int index) {
        long npc = npcAddress(index);
        if (npc == 0) {
            return "";
        }
        String own = structs.string(npc + aNpcName);
        if (!own.isEmpty()) {
            return own;
        }
        long type = safePointer(npc + npcType);
        return type == 0 ? "" : structs.string(type + npcTypeName);
    }

    /** NPC definition id (its {@code NpcType.id}; the wiki id), or -1 if unavailable. */
    public int npcTypeId(int index) {
        long npc = npcAddress(index);
        if (npc == 0) {
            return -1;
        }
        long type = safePointer(npc + npcType);
        return type == 0 ? -1 : safeInt(type + npcTypeId, -1);
    }

    public MapCoord npcCoord(int index) {
        return actorCoord(npcAddress(index));
    }

    public Npc getNpcObj(int index) {
        long npc = npcAddress(index);
        if (npc == 0) {
            return null;
        }
        return new Npc(index, npcTypeId(index), npcName(index), actorCoord(npc));
    }

    public List<Npc> getNpcs() {
        List<Npc> npcs = new ArrayList<>();
        for (int index : getNpcIdAll()) {
            Npc npc = getNpcObj(index);
            if (npc != null) {
                npcs.add(npc);
            }
        }
        return npcs;
    }

    public Mapping mapping() {
        return mapping;
    }

    // ---- internals -----------------------------------------------------------------------------

    private long clientBase() {
        return safePointer(memory.moduleBase() + clientRva);
    }

    private long topWorld() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cTopWorld);
    }

    private long playerAddress(int index) {
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wPlayers, index);
    }

    private long npcAddress(int index) {
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wNpcs, index);
    }

    private MapCoord actorCoord(long actor) {
        if (actor == 0) {
            return null;
        }
        try {
            long world = topWorld();
            int plane = world == 0 ? 0 : memory.readInt(world + wPlane);
            int x = memory.readInt(actor + aPathX);
            int z = memory.readInt(actor + aPathZ);
            return new MapCoord(plane, x, z);
        } catch (MemoryException e) {
            return null;
        }
    }

    private int[] readIndexArray(long base, int count, int limit) {
        if (base == 0 || count <= 0 || count > limit) {
            return new int[0];
        }
        try {
            byte[] bytes = memory.read(base, count * 4);
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = (bytes[i * 4] & 0xff)
                        | (bytes[i * 4 + 1] & 0xff) << 8
                        | (bytes[i * 4 + 2] & 0xff) << 16
                        | (bytes[i * 4 + 3] & 0xff) << 24;
            }
            return indices;
        } catch (MemoryException e) {
            return new int[0];
        }
    }

    private long safePointer(long address) {
        try {
            return memory.readPointer(address);
        } catch (MemoryException e) {
            return 0;
        }
    }

    private int safeInt(long address, int fallback) {
        try {
            return memory.readInt(address);
        } catch (MemoryException e) {
            return fallback;
        }
    }
}
