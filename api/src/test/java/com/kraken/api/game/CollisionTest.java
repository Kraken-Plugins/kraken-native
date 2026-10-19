package com.kraken.api.game;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.GameFixture.SCENERY_FLAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code osrs.World} collision maps and the {@code osrs.Collision} checks over the {@link GameFixture}'s grids: level 0
 * has a line of scenery at x = 4 for z = 1..6, and every grid's outer ring reads as unloaded.
 */
class CollisionTest {

    private GameFixture game;
    private World world;
    private Collision collision;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        Client client = new Client(game.memory, game.map);
        world = new World(client);
        collision = new Collision(client);
    }

    @Test
    void copiesEveryLevelsGrid() {
        assertEquals(4, world.getCollisionMaps().size());
        assertNull(world.getCollisionMap(4));
        assertNull(world.getCollisionMap(-1));
        CollisionMap map = world.getCollisionMap(0);
        assertEquals(0, map.getLevel());
        assertEquals(GameFixture.COLLISION_ORIGIN, map.getOriginX());
        assertEquals(GameFixture.COLLISION_ORIGIN, map.getOriginZ());
        assertEquals(GameFixture.COLLISION_SIZE, map.getSizeX());
        assertEquals(GameFixture.COLLISION_SIZE, map.getSizeZ());
    }

    @Test
    void bitFlagsAreReadAtTheAskedTile() {
        CollisionMap map = world.getCollisionMap(0);
        assertEquals(SCENERY_FLAGS, map.getBitFlags(4, 3));
        assertEquals(0, map.getBitFlags(3, 3));
        assertEquals(CollisionFlag.UNLOADED, map.getBitFlags(-1, 0), "the ring outside the scene");
        assertEquals(CollisionFlag.UNLOADED, map.getBitFlags(20, 20), "outside the grid");
        assertTrue(CollisionFlag.isSet(map.getBitFlags(4, 3), CollisionFlag.SQ_BLOCKED));
        assertEquals(SCENERY_FLAGS, collision.getBitFlags(new MapCoord(0, 4, 3)));
        assertEquals(CollisionFlag.UNLOADED, collision.getBitFlags(new MapCoord(9, 4, 3)));
    }

    @Test
    void aCopyDoesNotFollowLaterChanges() {
        CollisionMap map = world.getCollisionMap(0);
        game.setCollision(0, 4, 3, 0);
        assertEquals(SCENERY_FLAGS, map.getBitFlags(4, 3));
        assertEquals(0, collision.getBitFlags(new MapCoord(0, 4, 3)), "the live check sees the change");
    }

    @Test
    void lineOfWalkStopsAtScenery() {
        CollisionMap map = world.getCollisionMap(0);
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
        CollisionMap map = world.getCollisionMap(0);
        assertTrue(map.lineOfSight(1, 3, 3, 3));
        assertFalse(map.lineOfSight(1, 3, 6, 3));
        assertTrue(map.lineOfSight(1, 0, 6, 0));
        assertTrue(map.lineOfSight(6, 7, 1, 7));
        assertFalse(map.lineOfSight(-5, 0, 1, 0), "the start is outside the grid");
    }

    @Test
    void liveChecksMatchTheCopy() {
        CollisionMap map = world.getCollisionMap(0);
        int[][] lines = {{1, 3, 3, 3}, {1, 3, 6, 3}, {3, 3, 4, 3}, {1, 0, 6, 0}, {3, 0, 5, 2}, {6, 7, 1, 7}};
        for (int[] l : lines) {
            MapCoord a = new MapCoord(0, l[0], l[1]);
            MapCoord b = new MapCoord(0, l[2], l[3]);
            assertEquals(map.lineOfWalk(l[0], l[1], l[2], l[3]), collision.lineOfWalk(a, b));
            assertEquals(map.lineOfSight(l[0], l[1], l[2], l[3]), collision.lineOfSight(a, b));
        }
        assertFalse(collision.lineOfWalk(new MapCoord(0, 1, 0), new MapCoord(1, 2, 0)), "different levels");
        assertFalse(collision.lineOfSight(new MapCoord(0, 1, 0), new MapCoord(1, 2, 0)), "different levels");
    }
}
