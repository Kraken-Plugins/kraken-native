package com.kraken.api.game;

import com.kraken.api.FakeMemory;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.TestSupport.seedMapping;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lays out a minimal Client → World → {Npc, Player} object graph in a fake image using the real mapping's
 * offsets, then checks that {@link Client}'s Lua-mirroring methods follow the chains correctly. This covers
 * the plumbing (pointer following, the player-name pointer at +0x718, the NPC name fallback to its type, the
 * hash-map lookups) independently of whether the mapped offset values themselves are correct — that is what
 * the live client verifies.
 */
class ClientReadTest {

    private static final long MODULE_BASE = 0x140000000L;
    private static final long CLIENT = 0x200000000L;
    private static final long WORLD = 0x300000000L;
    private static final long NPC = 0x400000000L;
    private static final long NPC_TYPE = 0x500000000L;
    private static final long PLAYER = 0x600000000L;
    private static final long PLAYER_NAME = 0x610000000L;
    private static final long BUCKETS_NPC = 0x700000000L;
    private static final long NODE_NPC = 0x710000000L;
    private static final long BUCKETS_PLAYER = 0x720000000L;
    private static final long NODE_PLAYER = 0x730000000L;
    private static final long NPC_INDICES = 0x740000000L;

    private Mapping map;
    private FakeMemory memory;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        map = seedMapping();
        memory = new FakeMemory();
        memory.setModuleBase(MODULE_BASE);

        int buckets = map.container("hash_map.buckets");
        int bucketCount = map.container("hash_map.bucketCount");
        int nodeKey = map.container("hash_map.node.key");
        int nodeValue = map.container("hash_map.node.value");
        int nodeNext = map.container("hash_map.node.next");

        // Client pointer slot and the Client object
        memory.alloc(MODULE_BASE + map.global("client"), 8);
        memory.alloc(CLIENT, 0xd000);
        memory.putLong(MODULE_BASE + map.global("client"), CLIENT);
        memory.putInt(CLIENT + map.offset("Client.gameState"), 30);
        memory.putInt(CLIENT + map.offset("Client.tickCount"), 123);
        memory.putLong(CLIENT + map.offset("Client.topLevelWorld"), WORLD);
        memory.putInt(CLIENT + map.offset("Client.localPlayerIndex"), 7);
        memory.putInt(CLIENT + map.offset("Client.playerIndexCount"), 1);
        memory.putInt(CLIENT + map.offset("Client.playerIndices"), 7);

        // World, its tables and NPC index list
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

        memory.alloc(NPC_INDICES, 4);
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

        client = new Client(memory, map);
    }

    @Test
    void readsClientScalars() {
        assertTrue(client.isLoggedIn());
        assertEquals(30, client.gameState());
        assertEquals(123, client.getTickCount());
        assertEquals(7, client.getLocalPlayerIndex());
        assertEquals(7, client.playerFindSelf());
    }

    @Test
    void listsLoadedEntities() {
        assertArrayEquals(new int[]{5}, client.getNpcIdAll());
        assertArrayEquals(new int[]{7}, client.getPlayerIdAll());
    }

    @Test
    void readsNpcThroughTypeFallback() {
        assertEquals("Goblin", client.npcName(5));
        assertEquals(3029, client.npcTypeId(5));
        assertEquals(new MapCoord(0, 3222, 3218), client.npcCoord(5));

        Npc npc = client.getNpcObj(5);
        assertNotNull(npc);
        assertEquals("Goblin", npc.name());
        assertEquals(5, npc.index());
    }

    @Test
    void readsPlayerNameBehindPointer() {
        assertEquals("Zezima", client.playerName(7));
        assertEquals(new MapCoord(0, 3221, 3218), client.playerCoord(7));

        Player local = client.getLocalPlayer();
        assertNotNull(local);
        assertEquals("Zezima", local.name());
        assertEquals(7, local.index());
    }

    @Test
    void unknownIndicesAreSafe() {
        assertEquals("", client.npcName(999));
        assertEquals(-1, client.npcTypeId(999));
        assertEquals("", client.playerName(999));
    }
}
