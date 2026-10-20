package com.kraken.api.lua;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reads the {@link GameFixture}'s Client → World → {Npc, Player} graph through {@link ClientOp}. This covers the
 * plumbing (pointer following, the player-name pointer at +0x718, the NPC name fallback to its type, the hash-map
 * lookups) independently of whether the mapped offset values themselves are correct; that is what the live client
 * verifies.
 */
class ClientReadTest {

    private ClientOp client;

    @BeforeEach
    void setUp() throws IOException {
        GameFixture game = new GameFixture();
        client = new ClientOp(game.memory, game.map);
    }

    @Test
    void readsLocalPlayerIndex() {
        assertEquals(7, client.getLocalPlayerIndex());
        assertEquals(7, client.playerFindSelf());
    }

    @Test
    void listsLoadedEntities() {
        assertArrayEquals(new int[]{5, 6}, client.getNpcIdAll());
        assertArrayEquals(new int[]{7, 9, 10}, client.getPlayerIdAll());
    }

    @Test
    void readsNpcThroughTypeFallback() {
        assertEquals("Goblin", client.npcName(5));
        assertEquals(3029, client.npcTypeId(5));
        assertEquals(new MapCoord(0, 3222, 3218), client.npcCoord(5));

        Npc npc = client.getNpcObj(5);
        assertNotNull(npc);
        assertEquals("Goblin", npc.getName());
        assertEquals(5, npc.index());
    }

    @Test
    void readsPlayerNameBehindPointer() {
        assertEquals("Zezima", client.playerName(7));
        assertEquals(new MapCoord(0, 3221, 3218), client.playerCoord(7));

        Player local = client.getLocalPlayer();
        assertNotNull(local);
        assertEquals("Zezima", local.getName());
        assertEquals(7, local.index());
    }

    @Test
    void unknownIndicesAreSafe() {
        assertEquals("", client.npcName(999));
        assertEquals(-1, client.npcTypeId(999));
        assertEquals("", client.playerName(999));
    }
}
