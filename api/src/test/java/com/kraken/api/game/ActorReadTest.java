package com.kraken.api.game;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 3 reads over the {@link GameFixture}: fine coordinates and ground height, uids and creation cycles, route
 * length, the by-name lookups, and the {@code osrs.Entity}/{@code Npc}/{@code Player} methods on the live handles.
 */
class ActorReadTest {

    private static final int PLAYER_FINE_X = 2 * 128 + 32;
    private static final int PLAYER_FINE_Z = 3 * 128 + 96;

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        // Corners of tile (2, 3) on level 0: the player stands a quarter in from the west, three quarters north
        game.setCornerHeight(0, 2, 3, -100);
        game.setCornerHeight(0, 3, 3, -200);
        game.setCornerHeight(0, 2, 4, -300);
        game.setCornerHeight(0, 3, 4, -400);
    }

    @Test
    void fineCoordInterpolatesTheGroundAndSubtractsTheDecorOffset() {
        // south edge (-100*96 + -200*32) >> 7 = -125, north edge -325, then (-125*32 + -325*96) >> 7 = -275,
        // minus the daisies' offset of 10
        FineCoord fine = client.playerCoordFine(7);
        assertEquals(new FineCoord(PLAYER_FINE_X, -275 - GameFixture.DECOR_OFFSET, PLAYER_FINE_Z), fine);
        assertEquals(fine, client.coordFine());
        assertEquals(new MapCoord(0, 2, 3), fine.getMapCoordinate(0));
    }

    @Test
    void fineCoordUnderABridgeUsesTheLevelAbove() {
        game.setTileSettings(1, 2, 3, 2);
        for (int x = 2; x <= 3; x++) {
            for (int z = 3; z <= 4; z++) {
                game.setCornerHeight(1, x, z, -1000);
            }
        }
        assertEquals(-1000 - GameFixture.DECOR_OFFSET, client.playerCoordFine(7).y());
    }

    @Test
    void fineCoordOfSomethingMissingIsAllMinusOne() {
        assertEquals(FineCoord.MISSING, client.npcCoordFine(999));
        assertEquals(FineCoord.MISSING, client.playerCoordFine(999));
        assertTrue(client.npcCoordFine(999).isMissing());
    }

    @Test
    void coordUsesThePlayersOwnPlane() {
        assertEquals(new MapCoord(0, 3221, 3218), client.coord());
        game.memory.putInt(GameFixture.PLAYER + game.map.offset("Player.level"), 2);
        assertEquals(new MapCoord(2, 3221, 3218), client.coord());
        assertEquals(0, client.playerCoord(7).level(), "playerCoord takes the plane from the player's world");
    }

    @Test
    void readsNpcUidCreationCycleAndRouteLength() {
        assertEquals(3029 * 0x10000 + 5, client.npcUid(5));
        assertEquals(1000, client.npcCreationCycle(5));
        assertEquals(-1, client.npcUid(999));
        assertEquals(3, client.playerRouteLength(7));
        assertEquals(0, client.playerRouteLength(9));
        assertEquals(0, client.playerRouteLength(999));
    }

    @Test
    void npcNamesFollowTheMultiNpcTransform() {
        assertEquals("Goblin", client.npcName(6), "varbit 2 is 0: first child, 3029");
        game.setVarp(11, 111);
        assertEquals("Goblin chief", client.npcName(6), "varbit 2 is 1: second child, 3030");
        assertEquals(3100, client.npcTypeId(6), "the type id stays the base type's");
    }

    @Test
    void findsNpcsByExactName() {
        assertEquals(5, client.getNpcIdByName("Goblin"));
        assertArrayEquals(new int[]{5, 6}, client.getNpcIdByNameAll("Goblin"));
        assertArrayEquals(new int[0], client.getNpcIdByNameAll("goblin"), "case-sensitive");
        assertEquals(-1, client.getNpcIdByName("Man"));
        game.setVarp(11, 111);
        assertArrayEquals(new int[]{6}, client.getNpcIdByNameAll("Goblin chief"));
    }

    @Test
    void findsPlayersByNormalisedName() {
        assertEquals(7, client.getPlayerIdByName("ZEZIMA"));
        assertEquals(9, client.getPlayerIdByName("Bob"));
        assertEquals(-1, client.getPlayerIdByName("Nobody"));
    }

    @Test
    void npcHandleReadsEntityState() {
        Npc goblin = client.getNpcObj(5);
        assertNotNull(goblin);
        assertTrue(goblin.exists());
        assertEquals("Goblin", goblin.getName());
        assertEquals(7, goblin.isInteracting());
        assertEquals(Entity.TARGET_PLAYER, goblin.getInteractingType());
        assertTrue(goblin.isHealthbarRendered());
        assertTrue(goblin.isHeadbarEmpty());
        assertTrue(goblin.isDead());
        assertFalse(goblin.isRendered3D(), "Actor.hidden is set");
        assertTrue(goblin.isRendered2D());
        assertEquals(311, goblin.getType());
        assertEquals("Goblin", goblin.getTypeData().getName());
        assertFalse(goblin.isFollower());
        assertFalse(goblin.isLocalFollower());
    }

    @Test
    void headbarUpdatesExpireAndShowHealth() {
        Npc goblin = client.getNpcObj(5);
        game.memory.putInt(GameFixture.MODULE_BASE + game.map.global("cycle"), 400);
        assertFalse(goblin.isHeadbarEmpty(), "300 cycles after cycle 100, the update has expired");
        assertTrue(goblin.isHealthbarRendered(), "the bar itself is still listed");
        assertFalse(goblin.isDead());

        game.memory.putInt(GameFixture.MODULE_BASE + game.map.global("cycle"), GameFixture.CYCLE);
        game.memory.putInt(GameFixture.HEADBAR_UPDATE + game.map.offset("HeadbarEntry.health"), 5);
        assertFalse(goblin.isHeadbarEmpty());
        game.memory.putInt(GameFixture.MODULE_BASE + game.map.global("cycle"), 50);
        game.memory.putInt(GameFixture.HEADBAR_UPDATE + game.map.offset("HeadbarEntry.health"), 0);
        assertFalse(goblin.isHeadbarEmpty(), "the update hasn't started yet");
    }

    @Test
    void followersComeFromTheTypeAndTheFollowerVarps() {
        Npc pet = client.getNpcObj(6);
        assertTrue(pet.isFollower());
        assertTrue(pet.isLocalFollower(), "follower varp 12 holds 6");
        game.setVarp(12, 0x10007);
        assertFalse(pet.isLocalFollower(), "only the low 16 bits count: 7");
        game.setVarp(12, 0x10006);
        assertTrue(pet.isLocalFollower());
    }

    @Test
    void playerHandlesReadRelations() {
        Player self = client.getLocalPlayer();
        Player bob = client.getPlayerObj(9);
        Player troll = client.getPlayerObj(10);
        assertTrue(self.isLocalPlayer());
        assertFalse(bob.isLocalPlayer());
        assertEquals("Bob", bob.getName());
        assertTrue(bob.isOnlineFriend(), "not cached yet: found in the friend list, on world 301");
        assertFalse(troll.isOnlineFriend(), "cached as no");
        assertTrue(troll.isIgnored());
        assertFalse(bob.isIgnored());
        assertFalse(bob.isFriendChat());
        assertFalse(bob.isGroup());

        game.memory.putInt(GameFixture.PLAYER + 9 * 0x10000L + game.map.offset("Player.groupState"), 1);
        assertTrue(bob.isGroup());
    }

    @Test
    void handlesFollowTheIndex() {
        assertNull(client.getNpcObj(999));
        assertNull(client.getPlayerObj(999));
        Npc goblin = client.getNpcObj(5);
        // Unload everything from the world's NPC table: the handle now finds nothing
        game.memory.putLong(GameFixture.BUCKETS_NPC, 0);
        assertFalse(goblin.exists());
        assertEquals("", goblin.getName());
        assertEquals(-1, goblin.isInteracting());
        assertFalse(goblin.isDead());
        assertNull(goblin.getCoord());
        assertNull(goblin.getTypeData());
    }
}
