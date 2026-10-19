package com.kraken.api.game;

import com.kraken.api.config.ConfigTypes;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the scene: which world holds a coordinate, the locs on each tile, the item piles on the ground and the
 * collision flags. {@link Client} (the {@code locFind}/{@code objFind} family), {@link Scene}, {@link World} and
 * {@link Collision} share one instance. Each method mirrors a step of a native handler; where it differs, its Javadoc
 * says so.
 *
 * <p>Coordinates come in three spaces. A <em>world</em> coordinate is an absolute tile (Lumbridge is near 3222, 3218).
 * The client's {@code MapCoord}s, and every coordinate this API returns, are <em>scene</em> coordinates: the tile
 * relative to the loaded map's base ({@link #mapBase()}), as actors store them. Sub-worlds such as boats have their own
 * base; their tiles are found from the world coordinate.
 *
 * <p>Keys added to the mapping for sections 5 and 6 are optional here: without them, just the methods that need them
 * return their "missing" values.
 */
final class SceneReader extends GameReader {

    /**
     * The tag kind of a loc (other kinds share the tile's object list: players, NPCs, item piles).
     */
    private static final int KIND_LOC = 2;
    private static final int KIND_MASK = 7;
    private static final int ID_MASK = 0xffff;
    private static final int MAX_OBJECTS = 256;
    private static final int MAX_PILE = 1024;
    private static final int MAX_GRID = 1024;
    private static final int MAX_COLLISION_MAPS = 16;
    private static final int MAX_LEVEL = 3;

    /**
     * Tile setting bit marking a tile under a bridge (read on level 1).
     */
    private static final int SETTING_BRIDGE = 2;

    /**
     * Tile setting bit that puts an obj's reported level at 0.
     */
    private static final int SETTING_FORCE_LEVEL0 = 8;

    final ActorReader actors;
    final ConfigTypes configs;

    private final int cTopWorld;
    private final int cWorldsMap;
    private final int cMapLoader;
    private final int mlBaseX;
    private final int mlBaseZ;

    private final int wScene;
    private final int wBaseX;
    private final int wBaseZ;
    private final int wWidth;
    private final int wHeight;
    private final int wPiles;
    private final int pileStride;
    private final int pileData;

    private final int scSizeX;
    private final int scSizeZ;
    private final int scLevels;
    private final int scTiles;
    private final int scBridgeTiles;
    private final int scEntryStride;
    private final int scEntryPtr;
    private final int scSettings;
    private final int scCollision;
    private final int scCollisionEnd;

    private final int tWall;
    private final int tWallDecor;
    private final int tGroundDecor;
    private final int tObjectCount;
    private final int tObjects;
    private final int tObjectStride;
    private final int tileBlock;
    private final int locTag;
    private final int tagIdShift;
    private final int tagKindShift;
    private final int goTag;
    private final int goOriginX;
    private final int goOriginZ;

    private final int listNext;
    private final int listValue;
    private final int itemId;
    private final int itemCount;

    private final int cmStride;
    private final int cmOriginX;
    private final int cmOriginZ;
    private final int cmSizeX;
    private final int cmSizeZ;
    private final int cmFlags;
    private final int cmColumnStride;
    private final int cmColumnData;

    SceneReader(Memory memory, Mapping mapping, ConfigTypes configs, ActorReader actors) {
        super(memory, mapping);
        this.actors = actors;
        this.configs = configs;

        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cWorldsMap = optionalOffset("Client.worlds.map");
        this.cMapLoader = optionalOffset("Client.mapLoader");
        this.mlBaseX = optionalOffset("MapLoader.baseX");
        this.mlBaseZ = optionalOffset("MapLoader.baseZ");

        this.wScene = optionalOffset("World.scene");
        this.wBaseX = optionalOffset("World.baseX");
        this.wBaseZ = optionalOffset("World.baseZ");
        this.wWidth = optionalOffset("World.width");
        this.wHeight = optionalOffset("World.height");
        this.wPiles = optionalOffset("World.objPiles");
        this.pileStride = optionalOffset("World.objPiles.stride");
        this.pileData = optionalOffset("World.objPiles.dataOffset");

        this.scSizeX = optionalOffset("Scene.sizeX");
        this.scSizeZ = optionalOffset("Scene.sizeZ");
        this.scLevels = optionalOffset("Scene.levels");
        this.scTiles = optionalOffset("Scene.tiles");
        this.scBridgeTiles = optionalOffset("Scene.bridgeTiles");
        this.scEntryStride = optionalOffset("Scene.tileEntryStride");
        this.scEntryPtr = optionalOffset("Scene.tileEntryPtrOffset");
        this.scSettings = optionalOffset("Scene.tileSettings");
        this.scCollision = optionalOffset("Scene.collisionMaps");
        this.scCollisionEnd = optionalOffset("Scene.collisionMapsEnd");

        this.tWall = optionalOffset("Tile.wall");
        this.tWallDecor = optionalOffset("Tile.wallDecor");
        this.tGroundDecor = optionalOffset("Tile.groundDecor");
        this.tObjectCount = optionalOffset("Tile.gameObjectCount");
        this.tObjects = optionalOffset("Tile.gameObjects");
        this.tObjectStride = optionalOffset("Tile.gameObjectEntryStride");
        this.tileBlock = 8 + Math.max(Math.max(tWall, tWallDecor), Math.max(Math.max(tGroundDecor, tObjects),
                tObjectCount));
        this.locTag = optionalOffset("Loc.tag");
        this.tagIdShift = optionalOffset("Loc.tagIdShift");
        this.tagKindShift = optionalOffset("Loc.tagKindShift");
        this.goTag = optionalOffset("GameObject.tag");
        this.goOriginX = optionalOffset("GameObject.originX");
        this.goOriginZ = optionalOffset("GameObject.originZ");

        this.listNext = containerOr("list.node.next", 0);
        this.listValue = containerOr("list.node.value", 0x18);
        this.itemId = optionalOffset("GroundItem.id");
        this.itemCount = optionalOffset("GroundItem.count");

        this.cmStride = optionalOffset("CollisionMapRecord.stride");
        this.cmOriginX = optionalOffset("CollisionMapRecord.originX");
        this.cmOriginZ = optionalOffset("CollisionMapRecord.originZ");
        this.cmSizeX = optionalOffset("CollisionMapRecord.sizeX");
        this.cmSizeZ = optionalOffset("CollisionMapRecord.sizeZ");
        this.cmFlags = optionalOffset("CollisionMapRecord.flags");
        this.cmColumnStride = optionalOffset("CollisionMapRecord.columnStride");
        this.cmColumnData = optionalOffset("CollisionMapRecord.columnData");
    }

    private int containerOr(String key, int fallback) {
        try {
            return mapping.container(key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * The top-level world.
     *
     * @return its address, or 0 if it can't be read
     */
    long topWorld() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cTopWorld);
    }

    /**
     * The loaded map's base tile: what the client adds to a {@code MapCoord} to get a world coordinate (and subtracts
     * in {@code objFind}). Kept by the client's map loader, which sets it on every map rebuild.
     *
     * @return {@code {baseX, baseZ}} in world tiles, or null if it can't be read or the mapping doesn't have it
     */
    int[] mapBase() {
        long client = clientBase();
        if (client == 0 || cMapLoader < 0 || mlBaseX < 0 || mlBaseZ < 0) {
            return null;
        }
        long loader = safePointer(client + cMapLoader);
        if (loader == 0) {
            return null;
        }
        int x = safeInt(loader + mlBaseX, Integer.MIN_VALUE);
        int z = safeInt(loader + mlBaseZ, Integer.MIN_VALUE);
        return x == Integer.MIN_VALUE || z == Integer.MIN_VALUE ? null : new int[]{x, z};
    }

    /**
     * Turns a scene coordinate into the world coordinate the client searches with, the way its {@code MapCoord}
     * packing does: the base is added, then the coordinate goes through the packed form {@code level << 28 | x << 14 | z}
     * and back, which keeps 2 bits of level and 14 of x and z.
     *
     * @param coord a scene coordinate
     * @return {@code {level, worldX, worldZ}}, or null if the map base can't be read
     */
    int[] toWorld(MapCoord coord) {
        int[] base = mapBase();
        if (base == null) {
            return null;
        }
        int packed = (coord.x() + base[0]) << 14 | (coord.z() + base[1]) | coord.level() << 28;
        return new int[]{packed >> 28 & 3, packed >> 14 & 0x3fff, packed & 0x3fff};
    }

    /**
     * The world whose area holds a world coordinate, as the client's lookup finds it: the first sub-world (a world
     * with an id other than 0, such as a boat) whose base and size cover it, otherwise the top-level world.
     *
     * @param worldX the world tile x
     * @param worldZ the world tile z
     * @return the world's address, or 0 if nothing is loaded
     */
    long worldAt(int worldX, int worldZ) {
        long client = clientBase();
        if (client == 0) {
            return 0;
        }
        long[] found = {0};
        if (cWorldsMap >= 0 && wBaseX >= 0 && wBaseZ >= 0 && wWidth >= 0 && wHeight >= 0) {
            actors.forEachNode(client + cWorldsMap, (id, world) -> {
                if (id == 0 || world == 0) {
                    return false;
                }
                int baseX = safeInt(world + wBaseX, Integer.MIN_VALUE);
                int baseZ = safeInt(world + wBaseZ, Integer.MIN_VALUE);
                if (baseX == Integer.MIN_VALUE || baseZ == Integer.MIN_VALUE || worldX < baseX || worldZ < baseZ) {
                    return false;
                }
                if (worldX < baseX + safeInt(world + wWidth, 0) && worldZ < baseZ + safeInt(world + wHeight, 0)) {
                    found[0] = world;
                    return true;
                }
                return false;
            });
        }
        return found[0] != 0 ? found[0] : safePointer(client + cTopWorld);
    }

    /**
     * A world's base tile.
     *
     * @param world the world
     * @return {@code {baseX, baseZ}} in world tiles, or null if they can't be read
     */
    int[] worldBase(long world) {
        if (world == 0 || wBaseX < 0 || wBaseZ < 0) {
            return null;
        }
        int x = safeInt(world + wBaseX, Integer.MIN_VALUE);
        int z = safeInt(world + wBaseZ, Integer.MIN_VALUE);
        return x == Integer.MIN_VALUE || z == Integer.MIN_VALUE ? null : new int[]{x, z};
    }

    /**
     * A world's size.
     *
     * @param world the world
     * @return {@code {width, height}} in tiles, or null if they can't be read
     */
    int[] worldSize(long world) {
        if (world == 0 || wWidth < 0 || wHeight < 0) {
            return null;
        }
        int width = safeInt(world + wWidth, -1);
        int height = safeInt(world + wHeight, -1);
        return width < 0 || height < 0 ? null : new int[]{width, height};
    }

    /**
     * The client's bounds check for a world's tiles: level 0-3, and x and z inside the world's width and height.
     *
     * @param world the world
     * @param level the level
     * @param x     the tile x within the world
     * @param z     the tile z within the world
     * @return true if the tile is inside the world
     */
    boolean inWorld(long world, int level, int x, int z) {
        if (world == 0 || wWidth < 0 || wHeight < 0 || level < 0 || level > MAX_LEVEL || x < 0 || z < 0) {
            return false;
        }
        return x < safeInt(world + wWidth, 0) && z < safeInt(world + wHeight, 0);
    }

    /**
     * A world's scene.
     *
     * @param world the world
     * @return the scene's address, or 0
     */
    long sceneOf(long world) {
        return world == 0 || wScene < 0 ? 0 : safePointer(world + wScene);
    }

    /**
     * A tile's settings byte.
     *
     * @param scene the scene
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @return the settings, or 0 if they can't be read
     */
    int settings(long scene, int level, int x, int z) {
        if (scene == 0 || scSettings < 0 || scSizeX < 0 || scSizeZ < 0) {
            return 0;
        }
        long table = safePointer(scene + scSettings);
        if (table == 0) {
            return 0;
        }
        long index = ((long) level * safeInt(scene + scSizeX, 0) + x) * safeInt(scene + scSizeZ, 0) + z;
        return index < 0 ? 0 : safeU8(table + index, 0);
    }

    /**
     * The level {@code locCoord} reports for a tile: one lower when the tile is under a bridge (level above 0 and the
     * level-1 setting's bridge bit set).
     *
     * @param scene the scene
     * @param level the tile's level
     * @param x     the tile x
     * @param z     the tile z
     * @return the level to report
     */
    int visibleLevel(long scene, int level, int x, int z) {
        boolean bridge = level > 0 && (settings(scene, 1, x, z) & SETTING_BRIDGE) != 0;
        return bridge ? level - 1 : level;
    }

    /**
     * The level {@code objCoord} reports for a tile: 0 when the tile's own setting has bit 3 set, otherwise as
     * {@link #visibleLevel}.
     *
     * @param scene the scene
     * @param level the tile's level
     * @param x     the tile x
     * @param z     the tile z
     * @return the level to report
     */
    int objLevel(long scene, int level, int x, int z) {
        if ((settings(scene, level, x, z) & SETTING_FORCE_LEVEL0) != 0) {
            return 0;
        }
        return visibleLevel(scene, level, x, z);
    }

    /**
     * A tile object, as the client's tile lookup finds it: level -1 reads the bridge table.
     *
     * @param scene the scene
     * @param level the level, or -1 for the bridge table
     * @param x     the tile x
     * @param z     the tile z
     * @return the tile's address, or 0 if there is none or it can't be read
     */
    long tile(long scene, int level, int x, int z) {
        if (scene == 0 || scTiles < 0 || scEntryStride < 0 || scEntryPtr < 0 || scSizeX < 0 || scSizeZ < 0) {
            return 0;
        }
        int sizeX = safeInt(scene + scSizeX, 0);
        int sizeZ = safeInt(scene + scSizeZ, 0);
        if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
            return 0;
        }
        long index;
        long table;
        if (level == -1) {
            if (scBridgeTiles < 0) {
                return 0;
            }
            index = (long) x * sizeZ + z;
            table = safePointer(scene + scBridgeTiles);
        } else {
            index = ((long) level * sizeX + x) * sizeZ + z;
            table = safePointer(scene + scTiles);
        }
        return table == 0 ? 0 : safePointer(table + index * scEntryStride + scEntryPtr);
    }

    private boolean hasLocs() {
        return tWall >= 0 && tWallDecor >= 0 && tGroundDecor >= 0 && locTag >= 0 && tagIdShift >= 0;
    }

    /**
     * The loc id in a tag.
     *
     * @param tag the 64-bit tag
     * @return the id (16 bits)
     */
    int tagId(long tag) {
        return (int) (tag >>> tagIdShift) & ID_MASK;
    }

    /**
     * Whether a tag belongs to a loc (the tile's object list also holds actors and item piles).
     *
     * @param tag the 64-bit tag
     * @return true if the tag's kind is a loc's (or the mapping can't tell)
     */
    boolean isLocTag(long tag) {
        return tagKindShift < 0 || ((int) (tag >>> tagKindShift) & KIND_MASK) == KIND_LOC;
    }

    /**
     * The client's {@code locFind} tile search: which of a tile's slots holds a loc of the given type, checked in the
     * client's order (wall decoration, wall, ground decoration, then the game object whose origin is this tile).
     *
     * @param scene the scene
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @param type  the loc type id
     * @return the layer ({@link Loc#WALL} and friends), or -1 if the tile has no such loc
     */
    int findLoc(long scene, int level, int x, int z, int type) {
        long tile = tile(scene, level, x, z);
        if (tile == 0 || !hasLocs()) {
            return -1;
        }
        if (slotId(tile + tWallDecor) == type) {
            return Loc.WALL_DECOR;
        }
        if (slotId(tile + tWall) == type) {
            return Loc.WALL;
        }
        if (slotId(tile + tGroundDecor) == type) {
            return Loc.GROUND_DECOR;
        }
        long object = gameObject(tile, x, z);
        if (object != 0 && tagId(safeLong(object + goTag, 0)) == type) {
            return Loc.GAME_OBJECT;
        }
        return -1;
    }

    private int slotId(long slot) {
        long loc = safePointer(slot);
        return loc == 0 ? -1 : tagId(safeLong(loc + locTag, 0));
    }

    /**
     * The client's tile game-object lookup: the first object in the tile's list that is a loc and has this tile as its
     * origin.
     *
     * @param tile the tile
     * @param x    the tile x
     * @param z    the tile z
     * @return the game object's address, or 0
     */
    long gameObject(long tile, int x, int z) {
        if (tile == 0 || tObjectCount < 0 || tObjects < 0 || tObjectStride < 0 || goTag < 0 || goOriginX < 0
                || goOriginZ < 0) {
            return 0;
        }
        int count = safeInt(tile + tObjectCount, 0);
        long array = safePointer(tile + tObjects);
        if (count <= 0 || count > MAX_OBJECTS || array == 0) {
            return 0;
        }
        ByteBuffer entries = readBlock(array, count * tObjectStride);
        if (entries == null) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            long object = entries.getLong(i * tObjectStride + 8);
            if (object != 0 && isLocTag(safeLong(object + goTag, 0)) && safeInt(object + goOriginX, -1) == x
                    && safeInt(object + goOriginZ, -1) == z) {
                return object;
            }
        }
        return 0;
    }

    /**
     * Every loc on the scene's tiles in a rectangle of one level, slot by slot (walls, wall decorations, game objects
     * with their origin on the tile, ground decorations).
     *
     * @param scene the scene
     * @param level the level
     * @param x1    the lowest tile x
     * @param z1    the lowest tile z
     * @param x2    the highest tile x (inclusive)
     * @param z2    the highest tile z (inclusive)
     * @return the locs found, in tile order
     */
    List<Loc> locs(long scene, int level, int x1, int z1, int x2, int z2) {
        List<Loc> out = new ArrayList<>();
        if (scene == 0 || !hasLocs()) {
            return out;
        }
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                long tile = tile(scene, level, x, z);
                if (tile == 0) {
                    continue;
                }
                ByteBuffer t = readBlock(tile, tileBlock);
                if (t == null) {
                    continue;
                }
                MapCoord at = new MapCoord(level, x, z);
                addSlot(out, t.getLong(tWall), Loc.WALL, at);
                addSlot(out, t.getLong(tWallDecor), Loc.WALL_DECOR, at);
                addGameObjects(out, t, at);
                addSlot(out, t.getLong(tGroundDecor), Loc.GROUND_DECOR, at);
            }
        }
        return out;
    }

    /**
     * Every loc in the scene, on every level: the tile table is read in one go, then each tile once. Used to find loc
     * changes between ticks; {@link #locs} is cheaper for a small area.
     *
     * @param scene the scene
     * @return the locs, level by level and tile by tile; empty if the scene can't be read
     */
    List<Loc> allLocs(long scene) {
        List<Loc> out = new ArrayList<>();
        if (scene == 0 || !hasLocs() || scTiles < 0 || scEntryStride <= 0 || scEntryPtr < 0 || scSizeX < 0
                || scSizeZ < 0) {
            return out;
        }
        int sizeX = safeInt(scene + scSizeX, 0);
        int sizeZ = safeInt(scene + scSizeZ, 0);
        int levels = scLevels < 0 ? MAX_LEVEL + 1 : Math.min(MAX_LEVEL + 1, safeInt(scene + scLevels, 0));
        long table = safePointer(scene + scTiles);
        if (table == 0 || levels <= 0 || sizeX <= 0 || sizeZ <= 0 || sizeX > MAX_GRID || sizeZ > MAX_GRID) {
            return out;
        }
        ByteBuffer entries = readBlock(table, levels * sizeX * sizeZ * scEntryStride);
        if (entries == null) {
            return out;
        }
        for (int level = 0; level < levels; level++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    int index = (level * sizeX + x) * sizeZ + z;
                    long tile = entries.getLong(index * scEntryStride + scEntryPtr);
                    ByteBuffer t = tile == 0 ? null : readBlock(tile, tileBlock);
                    if (t == null) {
                        continue;
                    }
                    MapCoord at = new MapCoord(level, x, z);
                    addSlot(out, t.getLong(tWall), Loc.WALL, at);
                    addSlot(out, t.getLong(tWallDecor), Loc.WALL_DECOR, at);
                    addGameObjects(out, t, at);
                    addSlot(out, t.getLong(tGroundDecor), Loc.GROUND_DECOR, at);
                }
            }
        }
        return out;
    }

    private void addSlot(List<Loc> out, long loc, int layer, MapCoord at) {
        if (loc != 0) {
            long tag = safeLong(loc + locTag, -1);
            if (tag != -1) {
                out.add(new Loc(tagId(tag), layer, at));
            }
        }
    }

    private void addGameObjects(List<Loc> out, ByteBuffer tile, MapCoord at) {
        if (tObjectCount < 0 || tObjects < 0 || tObjectStride < 0 || goTag < 0 || goOriginX < 0 || goOriginZ < 0) {
            return;
        }
        int count = tile.getInt(tObjectCount);
        long array = tile.getLong(tObjects);
        if (count <= 0 || count > MAX_OBJECTS || array == 0) {
            return;
        }
        ByteBuffer entries = readBlock(array, count * tObjectStride);
        if (entries == null) {
            return;
        }
        int span = Math.max(goTag + 8, Math.max(goOriginX, goOriginZ) + 4) - Math.min(goTag, Math.min(goOriginX, goOriginZ));
        int first = Math.min(goTag, Math.min(goOriginX, goOriginZ));
        for (int i = 0; i < count; i++) {
            long object = entries.getLong(i * tObjectStride + 8);
            ByteBuffer o = object == 0 ? null : readBlock(object + first, span);
            if (o == null) {
                continue;
            }
            long tag = o.getLong(goTag - first);
            if (isLocTag(tag) && o.getInt(goOriginX - first) == at.x() && o.getInt(goOriginZ - first) == at.z()) {
                out.add(new Loc(tagId(tag), Loc.GAME_OBJECT, at));
            }
        }
    }

    private boolean hasPiles() {
        return wPiles >= 0 && pileStride >= 0 && pileData >= 0 && itemId >= 0 && itemCount >= 0;
    }

    /**
     * The sentinel of a tile's obj pile list: {@code [[[world + piles] + level*stride + data] + x*stride + data] +
     * z*stride}, as the client's pile lookup computes it. No bounds checks, as in the client; check
     * {@link #inWorld} first.
     *
     * @param world the world
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @return the list sentinel's address, or 0 if it can't be read
     */
    long pile(long world, int level, int x, int z) {
        if (world == 0 || !hasPiles()) {
            return 0;
        }
        long levels = safePointer(world + wPiles);
        long columns = levels == 0 ? 0 : safePointer(levels + (long) level * pileStride + pileData);
        long rows = columns == 0 ? 0 : safePointer(columns + (long) x * pileStride + pileData);
        return rows == 0 ? 0 : rows + (long) z * pileStride;
    }

    /**
     * The ground items on a tile, in the pile's order (the order {@code objFind} searches).
     *
     * @param world the world
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @return the items' addresses, empty if none
     */
    long[] pileItems(long world, int level, int x, int z) {
        long sentinel = pile(world, level, x, z);
        if (sentinel == 0) {
            return new long[0];
        }
        long[] items = new long[8];
        int n = 0;
        long node = safePointer(sentinel + listNext);
        while (node != 0 && node != sentinel && n < MAX_PILE) {
            long item = safePointer(node + listValue);
            if (item != 0) {
                if (n == items.length) {
                    items = java.util.Arrays.copyOf(items, n * 2);
                }
                items[n++] = item;
            }
            node = safePointer(node + listNext);
        }
        return java.util.Arrays.copyOf(items, n);
    }

    /**
     * A ground item's id.
     *
     * @param item the item
     * @return its id, or -1 if it can't be read
     */
    int itemId(long item) {
        return item == 0 || itemId < 0 ? -1 : safeInt(item + itemId, -1);
    }

    /**
     * A ground item's stack size.
     *
     * @param item the item
     * @return its count, or -1 if it can't be read
     */
    int itemCount(long item) {
        return item == 0 || itemCount < 0 ? -1 : safeInt(item + itemCount, -1);
    }

    /**
     * Every ground item on the tiles in a rectangle of one level of a world.
     *
     * @param world the world
     * @param level the level
     * @param x1    the lowest tile x
     * @param z1    the lowest tile z
     * @param x2    the highest tile x (inclusive)
     * @param z2    the highest tile z (inclusive)
     * @return the items, in tile order then pile order
     */
    List<GroundObj> objs(long world, int level, int x1, int z1, int x2, int z2) {
        List<GroundObj> out = new ArrayList<>();
        if (world == 0 || !hasPiles()) {
            return out;
        }
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!inWorld(world, level, x, z)) {
                    continue;
                }
                for (long item : pileItems(world, level, x, z)) {
                    int id = itemId(item);
                    if (id >= 0) {
                        out.add(new GroundObj(id, itemCount(item), new MapCoord(level, x, z)));
                    }
                }
            }
        }
        return out;
    }

    private boolean hasCollision() {
        return scCollision >= 0 && cmStride > 0 && cmOriginX >= 0 && cmOriginZ >= 0 && cmSizeX >= 0 && cmSizeZ >= 0
                && cmFlags >= 0 && cmColumnStride > 0 && cmColumnData >= 0;
    }

    /**
     * How many collision maps (levels) the scene holds.
     *
     * @param scene the scene
     * @return the number of records in the scene's collision map vector, 0 if it can't be read
     */
    int collisionMapCount(long scene) {
        if (scene == 0 || !hasCollision()) {
            return 0;
        }
        long begin = safePointer(scene + scCollision);
        if (begin == 0) {
            return 0;
        }
        if (scCollisionEnd < 0) {
            return MAX_LEVEL + 1;
        }
        long count = (safePointer(scene + scCollisionEnd) - begin) / cmStride;
        return count < 0 || count > MAX_COLLISION_MAPS ? 0 : (int) count;
    }

    private long collisionRecord(long scene, int level) {
        if (level < 0 || level >= collisionMapCount(scene)) {
            return 0;
        }
        long begin = safePointer(scene + scCollision);
        return begin == 0 ? 0 : begin + (long) level * cmStride;
    }

    /**
     * A copy of one level's collision flags, as {@code World.getCollisionMap} makes it.
     *
     * @param scene the scene
     * @param level the level
     * @return the copy, or null if the level has no collision map or it can't be read
     */
    CollisionMap collisionMap(long scene, int level) {
        long record = collisionRecord(scene, level);
        if (record == 0) {
            return null;
        }
        int sizeX = safeInt(record + cmSizeX, -1);
        int sizeZ = safeInt(record + cmSizeZ, -1);
        long columns = safePointer(record + cmFlags);
        if (sizeX < 0 || sizeZ < 0 || sizeX > MAX_GRID || sizeZ > MAX_GRID || (columns == 0 && sizeX > 0)) {
            return null;
        }
        ByteBuffer heads = sizeX == 0 ? ByteBuffer.allocate(0) : readBlock(columns, sizeX * cmColumnStride);
        if (heads == null) {
            return null;
        }
        heads.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[][] flags = new int[sizeX][];
        for (int x = 0; x < sizeX; x++) {
            int[] column = readInts(heads.getLong(x * cmColumnStride + cmColumnData), sizeZ);
            if (column == null) {
                return null;
            }
            flags[x] = column;
        }
        return new CollisionMap(level, safeInt(record + cmOriginX, 0), safeInt(record + cmOriginZ, 0), flags);
    }

    /**
     * One level's collision flags read straight from the client, a tile at a time, for the {@code osrs.Collision}
     * functions that only look at a few tiles.
     *
     * @param scene the scene
     * @param level the level
     * @return the grid, or null if the level has no collision map or it can't be read
     */
    CollisionLines.Grid liveCollision(long scene, int level) {
        long record = collisionRecord(scene, level);
        if (record == 0) {
            return null;
        }
        int originX = safeInt(record + cmOriginX, Integer.MIN_VALUE);
        int originZ = safeInt(record + cmOriginZ, Integer.MIN_VALUE);
        int sizeX = safeInt(record + cmSizeX, -1);
        int sizeZ = safeInt(record + cmSizeZ, -1);
        long columns = safePointer(record + cmFlags);
        if (originX == Integer.MIN_VALUE || originZ == Integer.MIN_VALUE || sizeX < 0 || sizeZ < 0 || columns == 0) {
            return null;
        }
        return new CollisionLines.Grid() {
            @Override
            public int originX() {
                return originX;
            }

            @Override
            public int originZ() {
                return originZ;
            }

            @Override
            public int sizeX() {
                return sizeX;
            }

            @Override
            public int sizeZ() {
                return sizeZ;
            }

            @Override
            public int at(int ix, int iz) {
                if (ix < 0 || iz < 0 || ix >= sizeX || iz >= sizeZ) {
                    return CollisionFlag.UNLOADED;
                }
                long column = safePointer(columns + (long) ix * cmColumnStride + cmColumnData);
                return column == 0 ? CollisionFlag.UNLOADED : safeInt(column + iz * 4L, CollisionFlag.UNLOADED);
            }
        };
    }
}
