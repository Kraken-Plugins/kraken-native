package com.kraken.api.collision;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.scene.WorldView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.SCENERY_FLAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collision maps and the line tests over the {@link GameFixture}'s grids: level 0 has a line of scenery at x = 4
 * for z = 1..6, and every grid's outer ring reads as unloaded. Map lookups take scene tiles; the world view's live
 * checks take world tiles.
 */
class CollisionTest {

    private GameFixture game;
    private Client client;
    private WorldView view;

    /**
     * Lays out the fixture and a client over it.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        view = client.getTopLevelWorldView();
    }

    /**
     * A world tile in the fixture's scene, from a scene tile on level 0.
     *
     * @param sceneX the scene x
     * @param sceneZ the scene z
     * @return the world tile
     */
    private static WorldPoint world(int sceneX, int sceneZ) {
        return new WorldPoint(BASE_X + sceneX, BASE_Z + sceneZ, 0);
    }

    @Test
    void copiesEveryLevelsGrid() {
        CollisionData[] maps = view.getCollisionMaps();
        assertEquals(4, maps.length);
        CollisionData map = maps[0];
        assertEquals(0, map.getPlane());
        assertEquals(GameFixture.COLLISION_ORIGIN, map.getOriginX());
        assertEquals(GameFixture.COLLISION_ORIGIN, map.getOriginY());
        assertEquals(GameFixture.SCENE_SIZE, map.getFlags().length, "the flags cover the scene; the ring is dropped");
        assertEquals(GameFixture.SCENE_SIZE, map.getFlags()[0].length);
    }

    @Test
    void bitFlagsAreReadAtTheAskedTile() {
        CollisionData map = view.getCollisionMaps()[0];
        assertEquals(SCENERY_FLAGS, map.getBitFlags(4, 3));
        assertEquals(0, map.getBitFlags(3, 3));
        assertEquals(CollisionDataFlag.UNLOADED, map.getBitFlags(-1, 0), "the ring outside the scene");
        assertEquals(CollisionDataFlag.UNLOADED, map.getBitFlags(20, 20), "outside the grid");
        assertTrue(CollisionDataFlag.isSet(map.getBitFlags(4, 3), CollisionDataFlag.SQ_BLOCKED));
        assertEquals(SCENERY_FLAGS, view.getCollisionFlags(world(4, 3)));
        assertEquals(CollisionDataFlag.UNLOADED, view.getCollisionFlags(new WorldPoint(BASE_X + 4, BASE_Z + 3, 9)));
    }

    @Test
    void aCopyDoesNotFollowLaterChanges() {
        CollisionData map = view.getCollisionMaps()[0];
        game.setCollision(0, 4, 3, 0);
        assertEquals(SCENERY_FLAGS, map.getBitFlags(4, 3));
        assertEquals(0, view.getCollisionFlags(world(4, 3)), "the live check sees the change");
    }

    @Test
    void lineOfWalkStopsAtScenery() {
        CollisionData map = view.getCollisionMaps()[0];
        assertTrue(map.lineOfWalk(1, 3, 3, 3));
        assertFalse(map.lineOfWalk(1, 3, 6, 3), "crosses the scenery at x = 4");
        assertFalse(map.lineOfWalk(3, 3, 4, 3), "ends on the scenery");
        assertTrue(map.lineOfWalk(1, 0, 6, 0), "row 0 is open");
        assertFalse(map.lineOfWalk(3, 0, 5, 2), "the diagonal steps north from (4, 1)");
        assertTrue(map.lineOfWalk(2, 2, 2, 2));
        assertFalse(map.lineOfWalk(1, 1, 30, 30), "the end is outside the grid");
    }

    @Test
    void lineOfSightStopsAtScenery() {
        CollisionData map = view.getCollisionMaps()[0];
        assertTrue(map.lineOfSight(1, 3, 3, 3));
        assertFalse(map.lineOfSight(1, 3, 6, 3));
        assertTrue(map.lineOfSight(1, 0, 6, 0));
        assertTrue(map.lineOfSight(6, 7, 1, 7));
        assertFalse(map.lineOfSight(-5, 0, 1, 0), "the start is outside the grid");
    }

    @Test
    void liveChecksMatchTheCopy() {
        CollisionData map = view.getCollisionMaps()[0];
        int[][] lines = {{1, 3, 3, 3}, {1, 3, 6, 3}, {3, 3, 4, 3}, {1, 0, 6, 0}, {3, 0, 5, 2}, {6, 7, 1, 7}};
        for (int[] l : lines) {
            WorldPoint a = world(l[0], l[1]);
            WorldPoint b = world(l[2], l[3]);
            assertEquals(map.lineOfWalk(l[0], l[1], l[2], l[3]), view.lineOfWalk(a, b));
            assertEquals(map.lineOfSight(l[0], l[1], l[2], l[3]), view.lineOfSight(a, b));
        }
        assertFalse(view.lineOfWalk(world(1, 0), new WorldPoint(BASE_X + 2, BASE_Z, 1)), "different levels");
        assertFalse(view.lineOfSight(world(1, 0), new WorldPoint(BASE_X + 2, BASE_Z, 1)), "different levels");
    }
}
