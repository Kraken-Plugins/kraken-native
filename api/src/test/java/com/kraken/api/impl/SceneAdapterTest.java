package com.kraken.api.impl;

import com.kraken.api.Client;
import com.kraken.api.CollisionData;
import com.kraken.api.CollisionDataFlag;
import com.kraken.api.DecorativeObject;
import com.kraken.api.GameFixture;
import com.kraken.api.GameObject;
import com.kraken.api.GroundObject;
import com.kraken.api.Perspective;
import com.kraken.api.Point;
import com.kraken.api.Scene;
import com.kraken.api.Tile;
import com.kraken.api.TileItem;
import com.kraken.api.TileObject;
import com.kraken.api.WallObject;
import com.kraken.api.WorldView;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.CollisionFlag;
import com.kraken.api.lua.LuaApi;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RuneLite-shaped scene over the {@link GameFixture}: tiles, the four object types, ground items, and the
 * collision maps, each checked against the fixture's scene tiles plus the map base.
 */
class SceneAdapterTest {

    private GameFixture game;
    private Client client;
    private Scene scene;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = Adapters.client(new LuaApi(game.memory, game.map));
        scene = client.getScene();
    }

    @Test
    void theSceneIsTheTopLevelWorldViews() {
        assertSame(client.getTopLevelWorldView().getScene(), scene);
        assertEquals(BASE_X, scene.getBaseX());
        assertEquals(BASE_Z, scene.getBaseY());
        assertEquals(SCENE_SIZE, scene.getSizeX());
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
    void objectsAreValuesWithRuneLitesHashLayout() {
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
    void groundItemsAreThePileInOrder() {
        List<TileItem> items = scene.getTile(0, 3, 3).getGroundItems();
        assertEquals(List.of(995, 1511, 995), items.stream().map(TileItem::getId).toList());
        assertEquals(List.of(100, 1, 5), items.stream().map(TileItem::getQuantity).toList());
        assertTrue(scene.getTile(0, 4, 3).getGroundItems().isEmpty());
        assertTrue(items.stream().noneMatch(TileItem::isHidden));
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
    void collisionDataFlagsAreTheLuaBits() {
        assertEquals(CollisionFlag.W_NW, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST);
        assertEquals(CollisionFlag.W_N, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
        assertEquals(CollisionFlag.W_NE, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST);
        assertEquals(CollisionFlag.W_E, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
        assertEquals(CollisionFlag.W_SE, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST);
        assertEquals(CollisionFlag.W_S, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
        assertEquals(CollisionFlag.W_SW, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST);
        assertEquals(CollisionFlag.W_W, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
        assertEquals(CollisionFlag.WALK_SCENARY, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
        assertEquals(CollisionFlag.WR_GROUND_DECOR, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION);
        assertEquals(CollisionFlag.WR_GRND, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR);
        assertEquals(CollisionFlag.V_N, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH);
        assertEquals(CollisionFlag.V_E, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST);
        assertEquals(CollisionFlag.V_S, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH);
        assertEquals(CollisionFlag.V_W, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST);
        assertEquals(CollisionFlag.VIS_SCENARY, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);
    }
}
