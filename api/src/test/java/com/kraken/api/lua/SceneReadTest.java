package com.kraken.api.lua;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.kraken.api.GameFixture.DECOR_LOC;
import static com.kraken.api.GameFixture.DOOR_LOC;
import static com.kraken.api.GameFixture.LAMP_LOC;
import static com.kraken.api.GameFixture.TREE_LOC;
import static com.kraken.api.GameFixture.WALL_LOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 6 reads over the {@link GameFixture}: {@code locFind}/{@code objFind} and the functions that read the active
 * loc or obj, and the Java scene listing.
 */
class SceneReadTest {

    private GameFixture game;
    private ClientOp client;
    private Scene scene;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new ClientOp(game.memory, game.map);
        scene = new Scene(client);
    }

    @Test
    void locFindMakesTheLocActive() {
        assertEquals(-1, client.locTypeId());
        assertNull(client.locCoord());
        assertEquals("", client.locName());

        assertTrue(client.locFind(DECOR_LOC, new MapCoord(0, 2, 3)));
        assertEquals(DECOR_LOC, client.locTypeId());
        assertEquals("Daisies", client.locName());
        assertEquals(new MapCoord(0, 2, 3), client.locCoord());
    }

    @Test
    void aMissKeepsThePreviousActiveLoc() {
        assertTrue(client.locFind(DOOR_LOC, new MapCoord(0, 1, 1)));
        assertFalse(client.locFind(DOOR_LOC, new MapCoord(0, 2, 2)));
        assertFalse(client.locFind(DOOR_LOC, new MapCoord(0, 8, 1)), "outside the world");
        assertFalse(client.locFind(DOOR_LOC, new MapCoord(1, 1, 1)), "another level");
        assertEquals(DOOR_LOC, client.locTypeId());
        assertEquals(new MapCoord(0, 1, 1), client.locCoord());
    }

    @Test
    void locOpsResolveConditionalOpsAndStripTags() {
        assertTrue(client.locFind(DOOR_LOC, new MapCoord(0, 1, 1)));
        assertEquals("Close", client.locGetOp(1, 1), "varp 10 is 100, inside the variant's 50-150");
        game.setVarpBits(10, 0);
        assertEquals("Open", client.locGetOp(1, 1));
        assertEquals("<col=ff0000>Knock</col><lt>3", client.locGetOp(1, 2));
        assertEquals("Knock<3", client.locGetOp(0, 2));
        assertEquals("", client.locGetOp(1, 3));
        assertEquals("", client.locGetOp(1, 6));
        assertEquals(1, client.locWidth());
        assertEquals(2, client.locLength());
    }

    @Test
    void gameObjectsAreFoundOnTheirOriginTileOnly() {
        assertTrue(client.locFind(TREE_LOC, new MapCoord(0, 5, 5)));
        assertEquals(2, client.locWidth());
        assertEquals("Oak", client.locName());
        assertFalse(client.locFind(TREE_LOC, new MapCoord(0, 6, 5)), "the oak's origin is (5, 5)");
        assertTrue(client.locFind(WALL_LOC, new MapCoord(0, 4, 4)));
    }

    @Test
    void locCoordReportsTheLevelBelowABridge() {
        assertTrue(client.locFind(LAMP_LOC, new MapCoord(1, 6, 6)));
        assertEquals(new MapCoord(0, 6, 6), client.locCoord());
    }

    @Test
    void coordinatesAreRelativeToTheMapBase() {
        // The map's base moves 8 tiles east of the world's: scene tile (-6, 3) is now world (3202, 3203), the daisies
        game.setMapBase(GameFixture.BASE_X + 8, GameFixture.BASE_Z);
        assertFalse(client.locFind(DECOR_LOC, new MapCoord(0, 2, 3)));
        assertTrue(client.locFind(DECOR_LOC, new MapCoord(0, -6, 3)));
        assertEquals(new MapCoord(0, 2, 3), client.locCoord(), "locCoord is relative to the world's own base");
    }

    @Test
    void objFindMakesTheFirstMatchingPileEntryActive() {
        assertEquals(-1, client.objTypeId());
        assertTrue(client.objFind(1511, new MapCoord(0, 3, 3)));
        assertEquals(1511, client.objTypeId());
        assertEquals(1, client.objCount());
        assertEquals("Logs", client.objName());
        assertEquals(client.ocOp(1511, 3), client.objGetOp(1, 3));
        assertEquals(new MapCoord(0, 3, 3), client.objCoord());

        assertTrue(client.objFind(995, new MapCoord(0, 3, 3)));
        assertEquals(100, client.objCount(), "the first coins in the pile");
        assertFalse(client.objFind(4151, new MapCoord(0, 3, 3)));
        assertFalse(client.objFind(995, new MapCoord(0, 3, 4)));
        assertEquals(995, client.objTypeId());
    }

    @Test
    void anObjThatLeftTheGroundReadsAsMissing() {
        assertTrue(client.objFind(995, new MapCoord(0, 3, 3)));
        game.removeFirstPileItem(0, 3, 3);
        assertEquals(-1, client.objTypeId());
        assertEquals(-1, client.objCount());
        assertEquals("", client.objName());
        assertEquals("", client.objGetOp(1, 3));
        assertEquals(new MapCoord(0, 3, 3), client.objCoord(), "the coordinate stays, as in the client");
    }

    @Test
    void stripTagsReplaysTheClientsLoop() {
        assertEquals("Take", ClientOp.stripTags("Take"));
        assertEquals("Chop down", ClientOp.stripTags("<col=ffffff>Chop down</col>"));
        assertEquals("a<b>c@d\ne\n", ClientOp.stripTags("a<lt>b<gt>c<at>d<br>e<n>"));
        assertEquals("abab<cd", ClientOp.stripTags("ab<cd"), "an unclosed tag repeats the text before it");
    }

    @Test
    void sceneConvertsBetweenSceneAndWorldTiles() {
        assertEquals(new MapCoord(0, GameFixture.BASE_X, GameFixture.BASE_Z), scene.getBase());
        assertEquals(new MapCoord(1, 3202, 3203), scene.toWorld(new MapCoord(1, 2, 3)));
        assertEquals(new MapCoord(1, 2, 3), scene.toScene(new MapCoord(1, 3202, 3203)));
        assertEquals(new Vector2(8, 8), scene.getSize());
    }

    @Test
    void sceneListsLocsOncePerOriginTile() {
        List<Loc> level0 = scene.getLocs(0, 0, 0, 7, 7);
        assertEquals(List.of(
                new Loc(DOOR_LOC, Loc.WALL, new MapCoord(0, 1, 1)),
                new Loc(DECOR_LOC, Loc.GROUND_DECOR, new MapCoord(0, 2, 3)),
                new Loc(WALL_LOC, Loc.WALL, new MapCoord(0, 4, 4)),
                new Loc(TREE_LOC, Loc.GAME_OBJECT, new MapCoord(0, 5, 5))), level0);
        assertEquals(List.of(new Loc(LAMP_LOC, Loc.WALL_DECOR, new MapCoord(1, 6, 6))),
                scene.getLocs(new MapCoord(1, 6, 6), 1));
    }

    @Test
    void allLocsCoversEveryLevel() {
        List<Loc> all = scene.getAllLocs();
        assertEquals(5, all.size());
        assertTrue(all.contains(new Loc(LAMP_LOC, Loc.WALL_DECOR, new MapCoord(1, 6, 6))));
        assertTrue(all.contains(new Loc(TREE_LOC, Loc.GAME_OBJECT, new MapCoord(0, 5, 5))));
    }

    @Test
    void activateLocMakesAListedLocActive() {
        client.activateLoc(new Loc(TREE_LOC, Loc.GAME_OBJECT, new MapCoord(0, 5, 5)));
        assertEquals(TREE_LOC, client.locTypeId());
        assertEquals("Oak", client.locName());
        assertEquals(new MapCoord(0, 5, 5), client.locCoord());
    }

    @Test
    void sceneListsGroundItemsInPileOrder() {
        MapCoord tile = new MapCoord(0, 3, 3);
        assertEquals(List.of(new GroundObj(995, 100, tile), new GroundObj(1511, 1, tile), new GroundObj(995, 5, tile)),
                scene.getObjs(tile, 2));
        assertTrue(scene.getObjs(new MapCoord(0, 6, 6), 1).isEmpty());
    }

    @Test
    void tileCentreSitsOnTheGround() {
        game.setCornerHeight(0, 2, 3, -100);
        game.setCornerHeight(0, 3, 3, -100);
        game.setCornerHeight(0, 2, 4, -100);
        game.setCornerHeight(0, 3, 4, -100);
        assertEquals(new FineCoord(2 * 128 + 64, -100, 3 * 128 + 64), scene.getTileCenter(new MapCoord(0, 2, 3)));
    }

    @Test
    void allObjsListsEveryPileOnEveryLevel() {
        game.setPile(1, 6, 2, new int[]{4151}, new int[]{1});
        game.setPile(0, 3, 5, new int[]{1511, 1511}, new int[]{1, 1});
        MapCoord coins = new MapCoord(0, 3, 3);
        MapCoord logs = new MapCoord(0, 3, 5);
        assertEquals(List.of(new GroundObj(995, 100, coins), new GroundObj(1511, 1, coins),
                new GroundObj(995, 5, coins), new GroundObj(1511, 1, logs), new GroundObj(1511, 1, logs),
                new GroundObj(4151, 1, new MapCoord(1, 6, 2))), scene.getAllObjs());
    }

    @Test
    void allObjsMatchesTheAreaListing() {
        game.setPile(0, 6, 6, new int[]{995}, new int[]{7});
        game.setPile(0, 3, 3, new int[0], new int[0]);
        assertEquals(scene.getObjs(0, 0, 0, 7, 7), scene.getAllObjs());
        assertEquals(List.of(new GroundObj(995, 7, new MapCoord(0, 6, 6))), scene.getAllObjs());
    }
}
