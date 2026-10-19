package com.kraken.api;

import com.kraken.api.mapping.Mapping;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.kraken.api.TestSupport.seedMapping;

/**
 * A small fake game laid out in a {@link FakeMemory} at the real seed mapping's offsets: the Client with a World holding
 * one NPC (index 5, a Goblin) and one player (index 7, Zezima), stats, a varp array, a varc store and an inventory
 * table (backpack 93 and bank 95 in one bucket, worn 94 in the other). Tests read it through the real API classes and
 * change it between reads with the setters.
 */
public final class GameFixture {

    public static final long MODULE_BASE = 0x140000000L;
    public static final long CLIENT = 0x200000000L;
    public static final long WORLD = 0x300000000L;
    public static final long NPC = 0x400000000L;
    public static final long NPC_TYPE = 0x500000000L;
    public static final long PLAYER = 0x600000000L;
    public static final long PLAYER_NAME = 0x610000000L;
    public static final long BUCKETS_NPC = 0x700000000L;
    public static final long NODE_NPC = 0x710000000L;
    public static final long BUCKETS_PLAYER = 0x720000000L;
    public static final long NODE_PLAYER = 0x730000000L;
    public static final long NPC_INDICES = 0x740000000L;
    public static final long VARPS = 0x800000000L;
    public static final long VARC_STORE = 0x810000000L;
    public static final long VARC_ENTRIES = 0x820000000L;
    public static final long VARC_STRING_HEAP = 0x830000000L;
    public static final long INV_BUCKETS = 0x900000000L;
    public static final long INV_NODES = 0x910000000L;

    public static final int VARP_COUNT = 64;
    /** Inventory slot arrays are allocated this big; an inventory's vectors use the first {@code length} slots. */
    private static final int INV_CAPACITY = 32;
    private static final int INV_NODE = 0x1000;

    public final Mapping map;
    public final FakeMemory memory = new FakeMemory();
    private final Map<Integer, Long> invNodes = new HashMap<>();

    public GameFixture() throws IOException {
        map = seedMapping();
        memory.setModuleBase(MODULE_BASE);
        buildClient();
        buildWorld();
        buildStats();
        buildVars();
        buildInventories();
    }

    // ---- setters -------------------------------------------------------------------------------------------

    public void setGameState(int state) {
        memory.putInt(CLIENT + map.offset("Client.gameState"), state);
    }

    public void setLoginToken(long token) {
        memory.putLong(CLIENT + map.offset("Client.loginToken"), token);
    }

    public void setTick(int tick) {
        memory.putInt(CLIENT + map.offset("Client.tickCount"), tick);
    }

    public void setStatsSent(boolean sent) {
        memory.putInt(CLIENT + map.offset("Client.statsValidGate"), sent ? 1 : 0);
    }

    public void setStat(int id, int xp, int level, int base) {
        memory.putInt(CLIENT + map.offset("Client.skillXp") + id * 4L, xp);
        memory.putInt(CLIENT + map.offset("Client.skillLevel") + id * 4L, level);
        memory.putInt(CLIENT + map.offset("Client.skillBase") + id * 4L, base);
    }

    public void setVarp(int id, int value) {
        memory.putInt(VARPS + id * 4L, value);
    }

    public void setWorldFlags(int flags) {
        memory.putInt(CLIENT + map.offset("Client.worldFlags"), flags);
    }

    /** Sets a slot of an existing inventory (93, 94 or 95), growing its vectors to cover it like the client does. */
    public void setInvSlot(int inv, int slot, int item, int count) {
        long node = invNodes.get(inv);
        long ids = memory.readLong(node + map.offset("Inventory.ids"));
        long counts = memory.readLong(node + map.offset("Inventory.counts"));
        int length = (int) ((memory.readLong(node + map.offset("Inventory.idsEnd")) - ids) / 4);
        for (int s = length; s <= slot; s++) {
            memory.putInt(ids + s * 4L, -1);
            memory.putInt(counts + s * 4L, 0);
        }
        if (slot >= length) {
            memory.putLong(node + map.offset("Inventory.idsEnd"), ids + (slot + 1) * 4L);
            memory.putLong(node + map.offset("Inventory.countsEnd"), counts + (slot + 1) * 4L);
        }
        memory.putInt(ids + slot * 4L, item);
        memory.putInt(counts + slot * 4L, count);
    }

    // ---- layout ------------------------------------------------------------------------------------------

    private void buildClient() {
        memory.alloc(MODULE_BASE + map.global("client"), 8);
        memory.alloc(CLIENT, 0x420000);
        memory.putLong(MODULE_BASE + map.global("client"), CLIENT);
        setGameState(30);
        setLoginToken(0x1234);
        setTick(123);
        memory.putLong(CLIENT + map.offset("Client.topLevelWorld"), WORLD);
        memory.putInt(CLIENT + map.offset("Client.localPlayerIndex"), 7);
        memory.putInt(CLIENT + map.offset("Client.playerIndexCount"), 1);
        memory.putInt(CLIENT + map.offset("Client.playerIndices"), 7);
    }

    private void buildWorld() {
        int buckets = map.container("hash_map.buckets");
        int bucketCount = map.container("hash_map.bucketCount");
        int nodeKey = map.container("hash_map.node.key");
        int nodeValue = map.container("hash_map.node.value");
        int nodeNext = map.container("hash_map.node.next");

        memory.alloc(WORLD, 0x100);
        memory.putInt(WORLD + map.offset("World.plane"), 0);

        long playerMap = WORLD + map.offset("World.players");
        memory.putLong(playerMap + buckets, BUCKETS_PLAYER);
        memory.putLong(playerMap + bucketCount, 1);
        memory.alloc(BUCKETS_PLAYER, 8);
        memory.alloc(NODE_PLAYER, 0x20);
        memory.putLong(BUCKETS_PLAYER, NODE_PLAYER);
        memory.putInt(NODE_PLAYER + nodeKey, 7);
        memory.putLong(NODE_PLAYER + nodeValue, PLAYER);
        memory.putLong(NODE_PLAYER + nodeNext, 0);

        long npcMap = WORLD + map.offset("World.npcs");
        memory.putLong(npcMap + buckets, BUCKETS_NPC);
        memory.putLong(npcMap + bucketCount, 1);
        memory.alloc(BUCKETS_NPC, 8);
        memory.alloc(NODE_NPC, 0x20);
        memory.putLong(BUCKETS_NPC, NODE_NPC);
        memory.putInt(NODE_NPC + nodeKey, 5);
        memory.putLong(NODE_NPC + nodeValue, NPC);
        memory.putLong(NODE_NPC + nodeNext, 0);

        memory.alloc(NPC_INDICES, 4 * 8);
        memory.putLong(WORLD + map.offset("World.npcIndices"), NPC_INDICES);
        memory.putInt(WORLD + map.offset("World.npcIndexCount"), 1);
        memory.putInt(NPC_INDICES, 5);

        // The NPC: own name left empty so npcName() falls back to the type name
        memory.alloc(NPC, 0x740);
        memory.putInt(NPC + map.offset("Actor.pathX"), 3222);
        memory.putInt(NPC + map.offset("Actor.pathZ"), 3218);
        memory.putSsoString(NPC + map.offset("Actor.name"), "");
        memory.putLong(NPC + map.offset("Npc.type"), NPC_TYPE);
        memory.alloc(NPC_TYPE, 0x30);
        memory.putInt(NPC_TYPE + map.offset("NpcType.id"), 3029);
        memory.putSsoString(NPC_TYPE + map.offset("NpcType.name"), "Goblin");

        // The player: name is behind a pointer at Player+0x718
        memory.alloc(PLAYER, 0x728);
        memory.putInt(PLAYER + map.offset("Actor.pathX"), 3221);
        memory.putInt(PLAYER + map.offset("Actor.pathZ"), 3218);
        memory.alloc(PLAYER_NAME, 0x18);
        memory.putLong(PLAYER + map.offsetOr("Player.name", 0x718), PLAYER_NAME);
        memory.putSsoString(PLAYER_NAME, "Zezima");
    }

    /** Attack 99, Hitpoints 10 boosted to 12; the rest level 1. */
    private void buildStats() {
        setStatsSent(true);
        int count = map.offset("Client.skillIndexMax");
        for (int id = 0; id < count; id++) {
            setStat(id, 0, 1, 1);
        }
        setStat(0, 13_034_431, 99, 99);
        setStat(3, 1_154, 12, 10);
    }

    /** 64 varps (varp n = n * 10); varcs 10 = 42, 20 = "hello", 30 = -5; world flags 0. */
    private void buildVars() {
        memory.alloc(MODULE_BASE + map.global("varps"), 8);
        memory.alloc(VARPS, VARP_COUNT * 4);
        memory.putLong(MODULE_BASE + map.global("varps"), VARPS);
        memory.alloc(MODULE_BASE + map.global("varpCount"), 4);
        memory.putInt(MODULE_BASE + map.global("varpCount"), VARP_COUNT);
        for (int id = 0; id < VARP_COUNT; id++) {
            setVarp(id, id * 10);
        }

        int stride = map.offset("VarcEntry.size");
        memory.putLong(CLIENT + map.offset("Client.varcs"), VARC_STORE);
        memory.alloc(VARC_STORE, 0x100);
        memory.alloc(VARC_ENTRIES, 3 * stride);
        memory.putLong(VARC_STORE + map.offset("Varcs.entries"), VARC_ENTRIES);
        memory.putLong(VARC_STORE + map.offset("Varcs.entriesEnd"), VARC_ENTRIES + 3L * stride);
        putIntVarc(VARC_ENTRIES, 10, 42);
        putStringVarc(VARC_ENTRIES + stride, 20, "hello");
        putIntVarc(VARC_ENTRIES + 2L * stride, 30, -5);

        setWorldFlags(0);
    }

    private void putIntVarc(long entry, int id, int value) {
        memory.putInt(entry + map.offset("VarcEntry.key"), id);
        memory.putInt(entry + map.offset("VarcEntry.value"), value);
        memory.putByte(entry + map.offset("VarcEntry.type"), 1);
    }

    private void putStringVarc(long entry, int id, String value) {
        memory.putInt(entry + map.offset("VarcEntry.key"), id);
        memory.putSsoString(entry + map.offset("VarcEntry.value"), value);
        memory.putByte(entry + map.offset("VarcEntry.type"), 2);
    }

    /**
     * Two buckets (plus the end sentinel): 94 alone in bucket 0, 93 then 95 chained in bucket 1. The backpack holds
     * logs (1511) in slot 0, coins (995) in slots 1 and 3, slot 2 empty; worn has a weapon in slot 3; the bank is empty.
     */
    private void buildInventories() {
        memory.alloc(MODULE_BASE + map.global("inventories.buckets"), 8);
        memory.alloc(MODULE_BASE + map.global("inventories.bucketCount"), 8);
        memory.putLong(MODULE_BASE + map.global("inventories.buckets"), INV_BUCKETS);
        memory.putLong(MODULE_BASE + map.global("inventories.bucketCount"), 2);
        memory.alloc(INV_BUCKETS, 3 * 8);
        memory.putLong(INV_BUCKETS + 2 * 8, 0xFFFF_FFFF_FFFF_FFFFL); // sentinel, never followed

        long n94 = addInventory(94, 0);
        long n93 = addInventory(93, 1);
        long n95 = addInventory(95, 2);
        memory.putLong(INV_BUCKETS, n94);
        memory.putLong(INV_BUCKETS + 8, n93);
        memory.putLong(n93 + map.offset("Inventory.next"), n95);

        setInvSlot(93, 0, 1511, 1);
        setInvSlot(93, 1, 995, 100);
        setInvSlot(93, 3, 995, 50);
        setInvSlot(94, 3, 4151, 1);
    }

    private long addInventory(int id, int index) {
        long node = INV_NODES + (long) index * INV_NODE;
        long ids = node + 0x100;
        long counts = ids + INV_CAPACITY * 4;
        memory.alloc(node, INV_NODE);
        memory.putInt(node + map.offset("Inventory.id"), id);
        memory.putLong(node + map.offset("Inventory.ids"), ids);
        memory.putLong(node + map.offset("Inventory.idsEnd"), ids);
        memory.putLong(node + map.offset("Inventory.counts"), counts);
        memory.putLong(node + map.offset("Inventory.countsEnd"), counts);
        invNodes.put(id, node);
        return node;
    }
}
