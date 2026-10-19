package com.kraken.api.game;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mirrors the client's Lua {@code osrs.ClientOp} namespace: players, NPCs and inventories, reproduced by following the
 * mapped offset chains in the live process. (Client-wide values such as stats, vars and the tick are in
 * {@link ClientState}, as they are in Lua.)
 *
 * <p>Entity chains start at the global {@code Client} pointer, then reach the current {@code World} and its
 * player/NPC tables. Inventories live in a separate global hash table keyed by inventory id. Reads are tolerant: an
 * unreadable or logged-out state yields empty results (empty arrays, "", null, -1) rather than exceptions.
 *
 * <p>Naming note: player names live behind a pointer at {@code Player+0x718} (the pointed-to object is an
 * EASTL string), whereas an NPC's own name is the inline string at {@code Npc+0x710}. The committed mapping
 * only has the shared {@code Actor.name = 0x710}, so the player path uses {@code Player.name} if the updater
 * ever adds it and otherwise falls back to {@code 0x718}.
 */
public final class Client extends GameReader {

    private static final int MAX_PLAYERS = 2048;
    private static final int MAX_NPCS = 65536;
    private static final long MAX_BUCKETS = 1 << 16;
    private static final int MAX_CHAIN = 10_000;
    private static final int MAX_SLOTS = 4096;

    /** Mirrors {@code ClientOp.WornSlots}: the worn-equipment inventory's (94) slot for each body part. */
    public enum WornSlots {
        HEAD(0), CAPE(1), AMULET(2), WEAPON(3), BODY(4), SHIELD(5), LEGS(7), GLOVES(9), BOOTS(10), RING(12), AMMO(13);

        private final int slot;

        WornSlots(int slot) {
            this.slot = slot;
        }

        public int slot() {
            return slot;
        }

        /** The worn slot with this index, or null for an index no body part uses. */
        public static WornSlots of(int slot) {
            for (WornSlots s : values()) {
                if (s.slot == slot) {
                    return s;
                }
            }
            return null;
        }
    }

    /** The backpack's inventory id. */
    public static final int INV_BACKPACK = 93;
    /** The worn-equipment inventory's id; see {@link WornSlots}. */
    public static final int INV_WORN = 94;
    /** The bank's inventory id. */
    public static final int INV_BANK = 95;

    private final int cTopWorld;
    private final int cLocalIndex;
    private final int cPlayerCount;
    private final int cPlayerIndices;

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

    private final long invBucketsRva;
    private final long invBucketCountRva;
    private final int invId;
    private final int invNext;
    private final int invIds;
    private final int invIdsEnd;
    private final int invCounts;
    private final int invCountsEnd;
    private final int invNodeSize;

    public Client(Memory memory, Mapping mapping) {
        super(memory, mapping);

        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cLocalIndex = mapping.offset("Client.localPlayerIndex");
        this.cPlayerCount = mapping.offset("Client.playerIndexCount");
        this.cPlayerIndices = mapping.offset("Client.playerIndices");

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

        this.invBucketsRva = mapping.global("inventories.buckets");
        this.invBucketCountRva = mapping.global("inventories.bucketCount");
        this.invId = mapping.offset("Inventory.id");
        this.invNext = mapping.offset("Inventory.next");
        this.invIds = mapping.offset("Inventory.ids");
        this.invIdsEnd = mapping.offset("Inventory.idsEnd");
        this.invCounts = mapping.offset("Inventory.counts");
        this.invCountsEnd = mapping.offset("Inventory.countsEnd");
        this.invNodeSize = 8 + Collections.max(List.of(invId, invNext, invIds, invIdsEnd, invCounts, invCountsEnd));
    }

    // ---- Local player ----------------------------------------------------------------------------

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

    // ---- Inventories (ClientOp.inv*) -------------------------------------------------------------

    /**
     * Mirrors {@code ClientOp.invGetObjId}: the item id in a slot of an inventory, or -1 if the slot is empty, past the
     * end of what the client holds, or the inventory isn't loaded.
     */
    public int invGetObjId(int invId, int slot) {
        long node = inventoryNode(invId);
        if (node == 0 || slot < 0) {
            return -1;
        }
        long begin = safePointer(node + invIds);
        long length = (safePointer(node + invIdsEnd) - begin) >> 2;
        return slot < length ? safeInt(begin + slot * 4L, -1) : -1;
    }

    /**
     * Mirrors {@code ClientOp.invGetNum}: the stack size in a slot of an inventory. Like the native handler, this is 0
     * (not the -1 the Lua docs mention) when the slot is empty or out of range or the inventory isn't loaded.
     */
    public int invGetNum(int invId, int slot) {
        long node = inventoryNode(invId);
        if (node == 0 || slot < 0) {
            return 0;
        }
        long begin = safePointer(node + invCounts);
        long length = (safePointer(node + invCountsEnd) - begin) >> 2;
        return slot < length ? safeInt(begin + slot * 4L, 0) : 0;
    }

    /** Mirrors {@code ClientOp.invTotal}: how many of an item an inventory holds across all its slots (0 if none). */
    public int invTotal(int invId, int objId) {
        if (objId == -1) {
            return 0;
        }
        Inventory inventory = getInventory(invId);
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (InvItem item : inventory.items()) {
            if (item.id() == objId) {
                total += item.count();
            }
        }
        return total;
    }

    /**
     * Every slot the client holds for an inventory, including empty ones (id -1, count 0), or null if the inventory
     * isn't loaded. Not a Lua function: a convenience over {@link #invGetObjId}/{@link #invGetNum}.
     *
     * <p>The client grows an inventory's slot arrays up to the highest slot the server has filled, so this can be
     * shorter than the inventory's defined size ({@code ClientOp.invSize}, which comes from the inventory's type).
     */
    public Inventory getInventory(int invId) {
        long node = inventoryNode(invId);
        return node == 0 ? null : readInventory(node);
    }

    /**
     * Every inventory the client currently holds (backpack, worn equipment, bank once opened, shops…), in no particular
     * order. Not a Lua function: the dev tools and the {@code ON_INV_TRANSMIT} event use it.
     */
    public List<Inventory> getInventories() {
        long base = memory.moduleBase();
        long buckets = safePointer(base + invBucketsRva);
        long count = safeLong(base + invBucketCountRva, 0) & 0xffffffffL; // the client divides by the low 32 bits
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return List.of();
        }
        ByteBuffer heads = readBlock(buckets, (int) count * 8);
        if (heads == null) {
            return List.of();
        }
        List<Inventory> inventories = new ArrayList<>();
        int guard = 0;
        for (int b = 0; b < count; b++) {
            long node = heads.getLong(b * 8);
            while (node != 0 && guard++ < MAX_CHAIN) {
                Inventory inventory = readInventory(node);
                if (inventory == null) {
                    break;
                }
                inventories.add(inventory);
                node = safePointer(node + invNext);
            }
        }
        return inventories;
    }

    // ---- internals -----------------------------------------------------------------------------

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

    /**
     * The inventory table's node for {@code id}, or 0. Same walk as the client's own lookup: the bucket is the
     * sign-extended id modulo the (32-bit) bucket count, then the chain is followed through {@code Inventory.next}.
     */
    private long inventoryNode(int id) {
        long base = memory.moduleBase();
        long buckets = safePointer(base + invBucketsRva);
        long count = safeLong(base + invBucketCountRva, 0) & 0xffffffffL;
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return 0;
        }
        long node = safePointer(buckets + Long.remainderUnsigned(id, count) * 8);
        for (int guard = 0; node != 0 && guard < MAX_CHAIN; guard++) {
            if (safeInt(node + invId, Integer.MIN_VALUE) == id) {
                return node;
            }
            node = safePointer(node + invNext);
        }
        return 0;
    }

    private Inventory readInventory(long node) {
        ByteBuffer n = readBlock(node, invNodeSize);
        if (n == null) {
            return null;
        }
        int id = n.getInt(invId);
        int[] ids = readVector(n.getLong(invIds), n.getLong(invIdsEnd));
        int[] counts = readVector(n.getLong(invCounts), n.getLong(invCountsEnd));
        if (ids == null || counts == null) {
            return null;
        }
        List<InvItem> items = new ArrayList<>(ids.length);
        for (int slot = 0; slot < ids.length; slot++) {
            items.add(new InvItem(ids[slot], slot, slot < counts.length ? counts[slot] : 0));
        }
        return new Inventory(id, List.copyOf(items));
    }

    /** An eastl::vector&lt;int&gt; given its begin/end pointers; null if it looks corrupt. */
    private int[] readVector(long begin, long end) {
        long length = (end - begin) >> 2;
        if (length < 0 || length > MAX_SLOTS || (length > 0 && begin == 0)) {
            return null;
        }
        return readInts(begin, (int) length);
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
        int[] indices = readInts(base, count);
        return indices == null ? new int[0] : indices;
    }
}
