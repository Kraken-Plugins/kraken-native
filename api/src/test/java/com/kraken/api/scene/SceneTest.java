package com.kraken.api.scene;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.collision.CollisionData;
import com.kraken.api.collision.CollisionDataFlag;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.DECOR_LOC;
import static com.kraken.api.GameFixture.DOOR_LOC;
import static com.kraken.api.GameFixture.LAMP_LOC;
import static com.kraken.api.GameFixture.SCENERY_FLAGS;
import static com.kraken.api.GameFixture.SCENE_SIZE;
import static com.kraken.api.GameFixture.TREE_LOC;
import static com.kraken.api.GameFixture.WALL_LOC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene over the {@link GameFixture}: tiles, the four kinds of tile object, ground items, the map base, and the
 * collision maps, each checked against the fixture's scene tiles plus the map base.
 */
class SceneTest {

    private GameFixture game;
    private Client client;
    private Scene scene;

    /**
     * Lays out the fixture and a client over it.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        scene = client.getScene();
    }

    @Test
    void theSceneIsTheTopLevelWorldViews() {
        assertSame(client.getTopLevelWorldView().getScene(), scene);
        assertEquals(BASE_X, scene.getBaseX());
        assertEquals(BASE_Z, scene.getBaseY());
        assertEquals(SCENE_SIZE, scene.getSizeX());
        assertEquals(SCENE_SIZE, scene.getSizeY());
        assertEquals(WorldView.TOPLEVEL, scene.getWorldViewId());
        Tile[][][] tiles = scene.getTiles();
        assertEquals(4, tiles.length);
        assertEquals(SCENE_SIZE, tiles[0].length);
        assertEquals(SCENE_SIZE, tiles[3][7].length);
        assertEquals(scene.getTile(2, 7, 1), tiles[2][7][1]);
        assertNull(scene.getTile(0, SCENE_SIZE, 0), "outside the scene");
        assertNull(scene.getTile(4, 0, 0), "no fifth plane");
    }

    @Test
    void sceneTilesConvertBetweenSceneAndWorldTiles() {
        WorldView view = client.getTopLevelWorldView();
        assertEquals(new WorldPoint(BASE_X + 2, BASE_Z + 3, 1), WorldPoint.fromScene(view, 2, 3, 1));
        assertEquals(new Point(2, 3), scene.getTile(1, 2, 3).getSceneLocation());
        assertEquals(new Point(SCENE_SIZE, SCENE_SIZE), new Point(scene.getSizeX(), scene.getSizeY()));
    }

    @Test
    void aTileKnowsWhereItIs() {
        Tile tile = scene.getTile(1, 2, 3);
        assertEquals(new WorldPoint(BASE_X + 2, BASE_Z + 3, 1), tile.getWorldLocation());
        assertEquals(new LocalPoint(2 * 128 + 64, 3 * 128 + 64, WorldView.TOPLEVEL), tile.getLocalLocation());
        assertEquals(new Point(2, 3), tile.getSceneLocation());
        assertEquals(1, tile.getPlane());
    }

    @Test
    void eachLayerIsItsOwnObjectType() {
        WallObject door = scene.getTile(0, 1, 1).getWallObject();
        assertEquals(DOOR_LOC, door.getId());
        assertEquals(new WorldPoint(BASE_X + 1, BASE_Z + 1, 0), door.getWorldLocation());
        assertEquals(new LocalPoint(192, 192, WorldView.TOPLEVEL), door.getLocalLocation());

        GroundObject daisies = scene.getTile(0, 2, 3).getGroundObject();
        assertEquals(DECOR_LOC, daisies.getId());
        assertEquals("Daisies", client.getObjectDefinition(daisies.getId()).getName());

        DecorativeObject lamp = scene.getTile(1, 6, 6).getDecorativeObject();
        assertEquals(LAMP_LOC, lamp.getId());
        assertEquals(1, lamp.getPlane(), "the tile's own level, under the bridge");
        assertEquals(0, scene.getTile(1, 6, 6).getRenderLevel(), "the level the tile is drawn on");

        assertEquals(WALL_LOC, scene.getTile(0, 4, 4).getWallObject().getId());
        assertNull(scene.getTile(0, 4, 4).getGroundObject());
        assertNull(scene.getTile(0, 0, 0).getWallObject());
        assertArrayEquals(new GameObject[0], scene.getTile(0, 0, 0).getGameObjects());
    }

    @Test
    void aGameObjectIsSizedByItsDefinition() {
        GameObject[] objects = scene.getTile(0, 5, 5).getGameObjects();
        assertEquals(1, objects.length, "the actor sharing the tile isn't an object");
        GameObject oak = objects[0];
        assertEquals(TREE_LOC, oak.getId());
        assertEquals(2, oak.sizeX());
        assertEquals(2, oak.sizeY());
        assertEquals(new Point(5, 5), oak.getSceneMinLocation());
        assertEquals(new Point(6, 6), oak.getSceneMaxLocation());
        assertEquals(6 * 128, oak.getX(), "the footprint's centre");
        assertEquals(6 * 128, oak.getY());
        assertEquals(new WorldPoint(BASE_X + 5, BASE_Z + 5, 0), oak.getWorldLocation(), "centre, rounded south-west");
        assertEquals(0, scene.getTile(0, 6, 5).getGameObjects().length, "listed on its south-west tile only");
    }

    @Test
    void objectsAreValuesWithAPackedHashLayout() {
        WallObject door = scene.getTile(0, 1, 1).getWallObject();
        assertEquals(door, scene.getTile(0, 1, 1).getWallObject());
        Set<TileObject> seen = new HashSet<>(List.of(door));
        assertTrue(seen.contains(scene.getTile(0, 1, 1).getWallObject()));
        assertNotEquals(door, scene.getTile(0, 4, 4).getWallObject());

        long hash = door.getHash();
        assertEquals(1, hash & 0x7f);
        assertEquals(1, hash >> 7 & 0x7f);
        assertEquals(0, hash >> TileObject.HASH_PLANE_SHIFT & 3);
        assertEquals(2, hash >> 16 & 7);
        assertEquals(DOOR_LOC, hash >> 20 & 0xffffffffL);
        assertEquals(WorldView.TOPLEVEL & 0xfff, hash >>> 52);
        assertEquals(1, scene.getTile(1, 6, 6).getDecorativeObject().getHash() >> TileObject.HASH_PLANE_SHIFT & 3);
    }

    @Test
    void objectsProjectThroughPerspective() {
        game.setCamera(5 * 128, -1500, -800, 0.5f, 0.866f, 0f, 1f);
        GameObject oak = scene.getTile(0, 5, 5).getGameObjects()[0];
        assertEquals(Perspective.getCanvasTileAreaPoly(client, oak.getLocalLocation(), 2, 2, 0, 0).getBounds(),
                oak.getCanvasTilePoly().getBounds());
        WallObject door = scene.getTile(0, 1, 1).getWallObject();
        assertEquals(Perspective.localToCanvas(client, door.getLocalLocation(), 0, 50), door.getCanvasLocation(50));
        assertNotNull(door.getCanvasLocation());
        assertEquals(4, door.getCanvasTilePoly().npoints);
    }

    @Test
    void tileObjectsListTheWholeSceneInOnePass() {
        List<TileObject> objects = scene.getTileObjects();
        assertEquals(List.of(DOOR_LOC, DECOR_LOC, WALL_LOC, TREE_LOC, LAMP_LOC),
                objects.stream().map(TileObject::getId).toList());
        assertEquals(scene.getTile(0, 1, 1).getWallObject(), objects.get(0));
        assertTrue(objects.get(3) instanceof GameObject);
    }

    @Test
    void tileObjectsInAnAreaAreListedOncePerOriginTile() {
        List<TileObject> level0 = scene.getTileObjects(0, 0, 0, 7, 7);
        assertEquals(List.of(DOOR_LOC, DECOR_LOC, WALL_LOC, TREE_LOC), level0.stream().map(TileObject::getId).toList());
        assertTrue(level0.get(1) instanceof GroundObject);
        assertTrue(level0.get(3) instanceof GameObject);
        assertEquals(List.of(LAMP_LOC), scene.getTileObjects(1, 5, 5, 7, 7).stream().map(TileObject::getId).toList());
    }

    @Test
    void groundItemsAreThePileInOrder() {
        List<TileItem> items = scene.getTile(0, 3, 3).getGroundItems();
        assertEquals(List.of(995, 1511, 995), items.stream().map(TileItem::getId).toList());
        assertEquals(List.of(100, 1, 5), items.stream().map(TileItem::getQuantity).toList());
        assertTrue(scene.getTile(0, 4, 3).getGroundItems().isEmpty());
        assertTrue(items.stream().noneMatch(TileItem::isHidden));
    }

    @Test
    void groundItemsInAnAreaKeepTheirPileOrder() {
        List<TileItem> items = scene.getGroundItems(0, 1, 1, 5, 5);
        assertEquals(List.of(995, 1511, 995), items.stream().map(TileItem::getId).toList());
        assertTrue(scene.getGroundItems(0, 5, 5, 7, 7).isEmpty());
    }

    @Test
    void anItemThatLeftThePileIsGone() {
        game.removeFirstPileItem(0, 3, 3);
        assertEquals(List.of(1511, 995),
                scene.getTile(0, 3, 3).getGroundItems().stream().map(TileItem::getId).toList());
    }

    @Test
    void aTileFromAnOldSceneReadsEmpty() {
        Tile door = scene.getTile(0, 1, 1);
        Tile pile = scene.getTile(0, 3, 3);
        game.setMapBase(BASE_X + 8, BASE_Z);
        assertNull(door.getWallObject());
        assertTrue(pile.getGroundItems().isEmpty());
        assertEquals(new WorldPoint(BASE_X + 1, BASE_Z + 1, 0), door.getWorldLocation(), "still where it was");
        assertEquals(DOOR_LOC, scene.getTile(0, 1, 1).getWallObject().getId(), "a fresh handle reads the new scene");
    }

    @Test
    void aMovedMapBaseMovesTheTilesWithIt() {
        // The map's base moves 8 tiles east: the scene tiles keep their contents, now at the new base's world tiles
        game.setMapBase(BASE_X + 8, BASE_Z);
        assertEquals(BASE_X + 8, scene.getBaseX());
        Tile tile = scene.getTile(0, 2, 3);
        assertEquals(new WorldPoint(BASE_X + 10, BASE_Z + 3, 0), tile.getGroundObject().getWorldLocation());
        assertEquals(new WorldPoint(BASE_X + 10, BASE_Z + 3, 0), tile.getWorldLocation());
    }

    @Test
    void groundHeightIsTheTilesCentreOnTheGround() {
        game.setCornerHeight(0, 2, 3, -100);
        game.setCornerHeight(0, 3, 3, -100);
        game.setCornerHeight(0, 2, 4, -100);
        game.setCornerHeight(0, 3, 4, -100);
        assertEquals(-100, Perspective.getTileHeight(client, new LocalPoint(2 * 128 + 64, 3 * 128 + 64,
                WorldView.TOPLEVEL), 0));
    }

    @Test
    void allGroundItemsListEveryPileOnEveryLevel() {
        game.setPile(1, 6, 2, new int[]{4151}, new int[]{1});
        game.setPile(0, 3, 5, new int[]{1511, 1511}, new int[]{1, 1});
        List<TileItem> all = scene.getGroundItems();
        assertEquals(List.of(995, 1511, 995, 1511, 1511, 4151), all.stream().map(TileItem::getId).toList());
        assertEquals(List.of(100, 1, 5, 1, 1, 1), all.stream().map(TileItem::getQuantity).toList());
        assertEquals(List.of(0, 0, 0, 0, 0, 1), all.stream().map(item -> item.getTile().getPlane()).toList());
    }

    @Test
    void allGroundItemsMatchTheAreaListing() {
        game.setPile(0, 6, 6, new int[]{995}, new int[]{7});
        game.setPile(0, 3, 3, new int[0], new int[0]);
        List<TileItem> all = scene.getGroundItems();
        assertEquals(List.of(995), all.stream().map(TileItem::getId).toList());
        assertEquals(List.of(7), all.stream().map(TileItem::getQuantity).toList());
        assertEquals(List.of(995), scene.getGroundItems(0, 0, 0, 7, 7).stream().map(TileItem::getId).toList());
        assertEquals(new WorldPoint(BASE_X + 6, BASE_Z + 6, 0), all.get(0).getTile().getWorldLocation());
    }

    @Test
    void collisionMapsAreIndexedBySceneTile() {
        CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
        assertEquals(4, maps.length);
        int[][] flags = maps[0].getFlags();
        assertEquals(SCENE_SIZE, flags.length);
        assertEquals(SCENE_SIZE, flags[0].length);
        assertEquals(SCENERY_FLAGS, flags[4][1]);
        assertEquals(0, flags[0][0], "the grid's ring outside the scene is dropped");
        assertTrue((flags[4][3] & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) != 0);
        assertTrue((flags[4][3] & CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL) != 0);
        game.setCollision(1, 2, 2, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR);
        assertEquals(0, maps[1].getFlags()[2][2], "a copy");
        assertEquals(CollisionDataFlag.BLOCK_MOVEMENT_FLOOR,
                client.getTopLevelWorldView().getCollisionMaps()[1].getFlags()[2][2]);
    }

    @Test
    void collisionDataFlagsKeepTheClientsBits() {
        assertEquals(1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST);
        assertEquals(2, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
        assertEquals(4, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST);
        assertEquals(8, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
        assertEquals(16, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST);
        assertEquals(32, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
        assertEquals(64, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST);
        assertEquals(128, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
        assertEquals(256, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
        assertEquals(262144, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION);
        assertEquals(2097152, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR);
        assertEquals(1024, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH);
        assertEquals(4096, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST);
        assertEquals(16384, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH);
        assertEquals(65536, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST);
        assertEquals(131072, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);
        assertFalse(CollisionDataFlag.isSet(0, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT));
    }
}
