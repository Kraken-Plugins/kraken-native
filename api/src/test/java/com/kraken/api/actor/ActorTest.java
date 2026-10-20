package com.kraken.api.actor;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldArea;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.FineCoord;
import com.kraken.api.scene.WorldView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actors over the {@link GameFixture}: the fine position and ground height the memory readers give, world and
 * local locations, the NPC and player state, followers and multi-NPC transforms, interaction targets, and handles
 * that stay on one actor (and remember it once it's gone).
 */
class ActorTest {

    private static final int PLAYER_FINE_X = 2 * 128 + 32;
    private static final int PLAYER_FINE_Z = 3 * 128 + 96;

    private GameFixture game;
    private Client client;

    /**
     * Lays out the fixture and a client over it, and sets the corners of the tile the local player stands on.
     *
     * @throws IOException if the bundled mapping can't be read
     */
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

    /**
     * An NPC handle by index, from the top-level world view.
     *
     * @param index the NPC's index
     * @return the handle, or null if no NPC is loaded in that slot
     */
    private NPC npc(int index) {
        return client.getTopLevelWorldView().npcs().byIndex(index);
    }

    /**
     * A player handle by index, from the top-level world view.
     *
     * @param index the player's index
     * @return the handle, or null if no player is loaded in that slot
     */
    private Player player(int index) {
        return client.getTopLevelWorldView().players().byIndex(index);
    }

    @Test
    void fineCoordInterpolatesTheGroundAndSubtractsTheDecorOffset() {
        // south edge (-100*96 + -200*32) >> 7 = -125, north edge -325, then (-125*32 + -325*96) >> 7 = -275,
        // minus the daisies' offset of 10
        FineCoord fine = client.getActorReader().fineCoord(client.getLocalPlayer().address());
        assertEquals(new FineCoord(PLAYER_FINE_X, -275 - GameFixture.DECOR_OFFSET, PLAYER_FINE_Z), fine);
        assertEquals(new LocalPoint(PLAYER_FINE_X, PLAYER_FINE_Z, WorldView.TOPLEVEL),
                client.getLocalPlayer().getLocalLocation());
    }

    @Test
    void fineCoordUnderABridgeUsesTheLevelAbove() {
        game.setTileSettings(1, 2, 3, 2);
        for (int x = 2; x <= 3; x++) {
            for (int z = 3; z <= 4; z++) {
                game.setCornerHeight(1, x, z, -1000);
            }
        }
        assertEquals(-1000 - GameFixture.DECOR_OFFSET,
                client.getActorReader().fineCoord(client.getLocalPlayer().address()).y());
    }

    @Test
    void fineCoordOfSomethingMissingIsAllMinusOne() {
        assertTrue(client.getActorReader().fineCoord(client.getActorReader().npc(999)).isMissing());
        assertTrue(client.getActorReader().fineCoord(client.getActorReader().player(999)).isMissing());
    }

    @Test
    void worldLocationIsTheSceneTilePlusTheBase() {
        NPC goblin = npc(5);
        assertEquals(new WorldPoint(3222 + BASE_X, 3218 + BASE_Z, 0), goblin.getWorldLocation());
        assertEquals(new WorldArea(3222 + BASE_X, 3218 + BASE_Z, 1, 1, 0), goblin.getWorldArea());
        Player self = client.getLocalPlayer();
        assertEquals(new WorldPoint(3221 + BASE_X, 3218 + BASE_Z, 0), self.getWorldLocation());
    }

    @Test
    void localLocationIsTheFinePositionWithoutTheHeight() {
        LocalPoint local = client.getLocalPlayer().getLocalLocation();
        assertEquals(new LocalPoint(2 * 128 + 32, 3 * 128 + 96, WorldView.TOPLEVEL), local);
        assertEquals(2, local.getSceneX());
        assertEquals(3, local.getSceneY());
    }

    @Test
    void handlesAreTheSameObjectWhileTheActorStays() {
        List<NPC> first = client.getNpcs();
        List<NPC> second = client.getNpcs();
        assertEquals(2, first.size());
        for (int i = 0; i < first.size(); i++) {
            assertSame(first.get(i), second.get(i));
        }
        assertSame(first.get(0), npc(5));
        assertSame(client.getLocalPlayer(), player(7));
        assertSame(client.getPlayers().get(0), client.getLocalPlayer());
    }

    @Test
    void npcReadsItsState() {
        NPC goblin = npc(5);
        assertEquals(5, goblin.getIndex());
        assertEquals(3029, goblin.getId());
        assertEquals("Goblin", goblin.getName());
        assertEquals("Goblin", goblin.getComposition().getName());
        assertEquals(311, goblin.getCategory());
        assertEquals(1000, goblin.getSpawnCycle());
        assertEquals(1, goblin.getFootprintSize());
        assertTrue(goblin.isHidden(), "its model is hidden");
        assertTrue(goblin.isDead(), "its health bar shows empty");
        assertTrue(goblin.isHealthbarRendered());
        assertTrue(goblin.isRendered2D());
        assertFalse(goblin.isFollower());
        assertFalse(goblin.isLocalFollower());
        assertSame(client.getTopLevelWorldView(), goblin.getWorldView());
    }

    @Test
    void interactingIsABooleanAndAHandleOnTheTarget() {
        NPC goblin = npc(5);
        assertTrue(goblin.isInteracting());
        assertEquals(7, goblin.getInteractingIndex());
        assertEquals(Actor.INTERACTING_PLAYER, goblin.getInteractingType());
        assertSame(client.getLocalPlayer(), goblin.getInteracting());
        Player self = client.getLocalPlayer();
        assertFalse(self.isInteracting());
        assertNull(self.getInteracting());
    }

    @Test
    void headbarUpdatesExpireAndShowHealth() {
        NPC goblin = npc(5);
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
    void aMultiNpcShowsItsTransformedComposition() {
        NPC multi = npc(6);
        assertEquals(3100, multi.getId(), "the base type");
        assertEquals(3029, multi.getTransformedComposition().getId(), "varbit 2 is 0: the first child");
        assertEquals("Goblin", multi.getName());
        game.setVarp(11, 1);
        assertEquals("Goblin chief", multi.getTransformedComposition().getName());
        assertEquals("Goblin chief", multi.getName());

        game.memory.putInt(GameFixture.NPC_MULTI_TYPE + game.map.offset("NpcType.multiVarbit"), -1);
        game.memory.putInt(GameFixture.NPC_MULTI_TYPE + game.map.offset("NpcType.multiVarp"), 13);
        assertNull(multi.getTransformedComposition(), "varp 13 is past the list: the last child, -1, hides it");
        assertEquals(3029, npc(5).getTransformedComposition().getId(), "no transform: the base type");
    }

    @Test
    void npcNamesFollowTheMultiNpcTransform() {
        assertEquals("Goblin", npc(6).getName(), "varbit 2 is 0: first child, 3029");
        game.setVarp(11, 111);
        assertEquals("Goblin chief", npc(6).getName(), "varbit 2 is 1: second child, 3030");
        assertEquals(3100, npc(6).getId(), "the type id stays the base type's");
    }

    @Test
    void followersComeFromTheTypeAndTheFollowerVarps() {
        NPC pet = npc(6);
        assertTrue(pet.isFollower());
        assertTrue(pet.isLocalFollower(), "follower varp 12 holds 6");
        game.setVarp(12, 0x10007);
        assertFalse(pet.isLocalFollower(), "only the low 16 bits count: 7");
        game.setVarp(12, 0x10006);
        assertTrue(pet.isLocalFollower());
    }

    @Test
    void theFollowerIsTheLocalPlayersPet() {
        assertSame(npc(6), client.getFollower());
        assertTrue(npc(6).isFollower());
        game.setVarp(12, 0);
        assertNull(client.getFollower(), "no follower varp holds an NPC index");
    }

    @Test
    void readsNpcUidCreationCycleAndRouteLength() {
        assertEquals(3029 * 0x10000 + 5, npc(5).getUid());
        assertEquals(1000, npc(5).getSpawnCycle());
        assertEquals(3, client.getLocalPlayer().getPathLength());
        assertEquals(0, player(9).getPathLength());
        assertEquals(-1, client.getActorReader().npcUid(client.getActorReader().npc(999)), "no NPC in that slot");
    }

    @Test
    void playerHandlesReadRelations() {
        Player self = client.getLocalPlayer();
        Player bob = player(9);
        Player troll = player(10);
        assertSame(client.getLocalPlayer(), self);
        assertNotSame(self, bob);
        assertEquals("Bob", bob.getName());
        assertTrue(bob.isOnlineFriend(), "not cached yet: found in the friend list, on world 301");
        assertFalse(troll.isOnlineFriend(), "cached as no");
        assertTrue(troll.isIgnored());
        assertFalse(bob.isIgnored());
        assertFalse(bob.isFriendsChatMember());
        assertFalse(bob.isClanMember());

        game.memory.putInt(GameFixture.PLAYER + 9 * 0x10000L + game.map.offset("Player.groupState"), 1);
        assertTrue(bob.isClanMember());
    }

    @Test
    void playersReadTheirState() {
        Player self = client.getLocalPlayer();
        assertEquals(7, self.getId());
        assertEquals("Zezima", self.getName());
        assertEquals(1, self.getFootprintSize());
        assertEquals(3, client.getPlayers().size());
        assertTrue(player(9).isOnlineFriend());
        assertFalse(player(9).isIgnored());
        assertTrue(player(10).isIgnored());
        assertFalse(self.isFriendsChatMember());
        assertFalse(self.isClanMember());
    }

    @Test
    void aDespawnedNpcKeepsWhatItLastRead() {
        NPC goblin = npc(5);
        WorldPoint at = goblin.getWorldLocation();
        assertEquals("Goblin", goblin.getName());
        assertEquals(3029, goblin.getId());
        assertEquals(1000, goblin.getSpawnCycle());

        game.setNpcLoaded(5, false);
        assertEquals("Goblin", goblin.getName());
        assertEquals(3029, goblin.getId());
        assertEquals(at, goblin.getWorldLocation());
        assertEquals(1000, goblin.getSpawnCycle());
        assertEquals("Goblin", goblin.getComposition().getName());
        assertFalse(goblin.isDead(), "live state reads as nothing");
        assertFalse(goblin.isInteracting());
        assertNull(npc(5));
        assertEquals(List.of(npc(6)), client.getNpcs());
    }

    @Test
    void anNpcNeverReadBeforeItLeftHasNothingToRemember() {
        NPC goblin = npc(5);
        game.setNpcLoaded(5, false);
        assertEquals("", goblin.getName());
        assertEquals(-1, goblin.getId());
        assertNull(goblin.getWorldLocation());
        assertNull(goblin.getWorldArea());
    }

    @Test
    void aNewNpcWhereTheOldOneWasGetsANewHandle() {
        NPC old = npc(5);
        assertEquals(1000, old.getSpawnCycle());
        game.setNpcSpawnCycle(5, 2000);
        NPC fresh = npc(5);
        assertNotSame(old, fresh);
        assertEquals(2000, fresh.getSpawnCycle());
        assertEquals(1000, old.getSpawnCycle(), "the old handle remembers its own NPC");
        assertFalse(old.isDead(), "and doesn't read the new one");
        assertTrue(fresh.isDead());
        assertSame(fresh, client.getNpcs().get(0));
    }

    @Test
    void aDespawnAndRespawnOfTheSameNpcKeepsItsHandle() {
        NPC goblin = npc(5);
        game.setNpcLoaded(5, false);
        assertNull(npc(5), "a read that finds the slot empty...");
        game.setNpcLoaded(5, true);
        assertSame(goblin, npc(5), "...doesn't cost the NPC its handle");
    }

    @Test
    void aRenamedPlayerInTheSameSlotIsAnotherPlayer() {
        Player bob = player(9);
        assertEquals("Bob", bob.getName());
        game.setPlayerName(9, "Alice", "alice");
        Player alice = player(9);
        assertNotSame(bob, alice);
        assertEquals("Alice", alice.getName());
        assertEquals("Bob", bob.getName(), "Bob's handle remembers Bob");
        assertFalse(bob.isOnlineFriend());
    }

    @Test
    void theTopLevelWorldView() {
        WorldView view = client.getTopLevelWorldView();
        assertSame(view, client.getWorldView(WorldView.TOPLEVEL));
        assertNull(client.getWorldView(0), "sub-worlds aren't wrapped yet");
        assertEquals(WorldView.TOPLEVEL, view.getId());
        assertTrue(view.isTopLevel());
        assertEquals(0, view.getPlane());
        assertEquals(BASE_X, view.getBaseX());
        assertEquals(BASE_Z, view.getBaseY());
        assertEquals(GameFixture.SCENE_SIZE, view.getSizeX());
        assertEquals(GameFixture.SCENE_SIZE, view.getSizeY());
        assertEquals(2, view.npcs().stream().count());
        assertEquals(3, view.players().stream().count());
    }

    @Test
    void theWorldViewKnowsWhatIsInItsScene() {
        WorldView view = client.getTopLevelWorldView();
        assertTrue(view.contains(new WorldPoint(BASE_X, BASE_Z, 0)));
        assertTrue(view.contains(new WorldPoint(BASE_X + 7, BASE_Z + 7, 3)), "any plane");
        assertFalse(view.contains(new WorldPoint(BASE_X + 8, BASE_Z, 0)));
        assertFalse(view.contains(new WorldPoint(BASE_X - 1, BASE_Z, 0)));
        assertTrue(view.contains(new LocalPoint(7 * 128 + 127, 0, WorldView.TOPLEVEL)));
        assertFalse(view.contains(new LocalPoint(8 * 128, 0, WorldView.TOPLEVEL)));
        assertFalse(view.contains(new LocalPoint(-1, 0, WorldView.TOPLEVEL)));
        assertFalse(view.contains(new LocalPoint(0, 0, 3)), "another world view's point");
    }

    @Test
    void localAndWorldPointsConvertThroughTheView() {
        WorldView view = client.getTopLevelWorldView();
        WorldPoint tile = new WorldPoint(BASE_X + 2, BASE_Z + 3, 0);
        LocalPoint centre = LocalPoint.fromWorld(client, tile);
        assertEquals(new LocalPoint(2 * 128 + 64, 3 * 128 + 64, WorldView.TOPLEVEL), centre);
        assertEquals(tile, WorldPoint.fromLocal(client, centre));
        assertEquals(tile, WorldPoint.fromScene(view, 2, 3, 0));
        assertNull(LocalPoint.fromWorld(client, tile.dz(1)), "another plane than the view's");
        assertNull(LocalPoint.fromWorld(client, new WorldPoint(BASE_X + 8, BASE_Z, 0)), "outside the scene");
    }

    @Test
    void lineTestsTakeWorldTiles() {
        WorldView view = client.getTopLevelWorldView();
        WorldPoint west = new WorldPoint(BASE_X + 1, BASE_Z + 3, 0);
        assertTrue(view.lineOfWalk(west, west.dx(2)));
        assertFalse(view.lineOfWalk(west, west.dx(5)), "crosses the scenery at scene x = 4");
        assertTrue(view.lineOfSight(west, west.dx(2)));
        assertFalse(view.lineOfSight(west, west.dx(5)));

        WorldArea area = new WorldArea(BASE_X + 3, BASE_Z + 3, 1, 1, 0);
        assertFalse(area.canTravelInDirection(view, 1, 0), "the scenery is east");
        assertTrue(area.canTravelInDirection(view, -1, 0));
        assertFalse(area.canTravelInDirection(view, 0, 0));
        assertTrue(area.hasLineOfSightTo(view, west));
        assertFalse(area.hasLineOfSightTo(view, west.dx(5)));
    }
}
