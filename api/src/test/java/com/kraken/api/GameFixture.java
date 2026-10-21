package com.kraken.api;

import com.kraken.api.actor.NPC;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.widgets.WidgetType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.kraken.api.TestSupport.seedMapping;

/**
 * A small fake game laid out in a {@link FakeMemory} at the real seed mapping's offsets, read through the real API
 * classes; tests change it between reads with the setters.
 *
 * <ul>
 *   <li>The Client, with a worlds table holding the one World (id 0, also the top-level world).
 *   <li>NPC 5, a Goblin (type 3029, category 311): hidden model, interacting with player 7, a health bar showing
 *       empty (so dead), created on cycle 1000. NPC 6, a multi-NPC whose type switches between 3029 and 3030 on
 *       varbit 2, and the local player's follower (follower varp 12 holds 6).
 *   <li>Player 7, Zezima, the local player: three queued steps, fine position on tile (2, 3) of the 8×8 scene whose
 *       corner heights the tests can check. Player 9, Bob, an online friend; player 10, Troll, ignored.
 *   <li>Stats, varps (both copies), varcs, the inventory table (backpack 93 and bank 95 in one bucket, worn 94 in the
 *       other), and a config archive with a few varbits, inventories, items, NPC types and locs.
 *   <li>The camera, the renderer (client 765×503 in a 1530×1006 window) and the viewport, set up so a point straight
 *       ahead of the eye projects to the viewport's centre.
 *   <li>The map's base at world tile ({@link #BASE_X}, {@link #BASE_Z}), which is also the world's base; the world is
 *       the 8×8 scene.
 *   <li>Locs: daisies (ground decoration) on (2, 3), a door (wall) on (1, 1), an oak (game object, also listed on
 *       (6, 5), and next to an actor on its own tile) on (5, 5), a wall on (4, 4), and on level 1 a lamp (wall
 *       decoration) on (6, 6) under a bridge. A pile on (3, 3): coins ×100, logs ×1, coins ×5.
 *   <li>Collision maps for 4 levels, one tile wider than the scene on each side (origin -1, like the client); level 0
 *       has a line of scenery across x = 4 for z = 1..6 that blocks walking and sight.
 *   <li>Interfaces: the store holds groups 149, 150 and 162, and 151 without components (as a group the client hasn't
 *       loaded). 149 and 162 are open, chained in one bucket of the open-interface map. 149:0 is a layer with dynamic
 *       children [0] (an item slot), [1] (a dynamic layer) and [2] (text inside [1]); 162:0 is a top-level layer
 *       holding 162:1 (text "All", drawn this frame, with an op) and 162:2 (hidden, its text on the heap).
 *   <li>Two graphics objects in the top-level world: a splash ({@link #SPLASH_GRAPHIC}) on tile (3, 4), playing frame
 *       3; and a cannon's smoke ({@link #SMOKE_GRAPHIC}) on tile (5, 2), raised 40 above the ground and not started.
 *   <li>Two projectiles in flight: a spell ({@link #SPELL_PROJECTILE}) from player 7 at NPC 5, placed and drawn; and
 *       an arrow ({@link #ARROW_PROJECTILE}) from one tile to another, hidden and not set off yet. And a keyed
 *       projectile ({@link #KEYED_PROJECTILE}, key {@link #PROJECTILE_KEY}) from player 7 at NPC 5, placed, with
 *       offsets at both ends.
 *   <li>The minimenu, closed, with one row per entry from {@link #MENU_ATTACK} to {@link #MENU_CANCEL} (top first),
 *       the row of {@link #MENU_USE_ON_GOBLIN} being a submenu group holding it. The last entry run is "Cancel", 100
 *       cycles ago.
 * </ul>
 */
public final class GameFixture {

    public static final long MODULE_BASE = 0x140000000L;
    public static final long CLIENT = 0x200000000L;
    public static final long WORLD = 0x300000000L;
    public static final long WORLDS_BUCKETS = 0x310000000L;
    public static final long WORLDS_NODE = 0x320000000L;
    public static final long SCENE = 0x330000000L;
    public static final long HEIGHTS = 0x340000000L;
    public static final long SETTINGS = 0x350000000L;
    public static final long TILES = 0x360000000L;
    public static final long TILE = 0x370000000L;
    public static final long DECOR = 0x380000000L;
    public static final long NPC = 0x400000000L;
    public static final long NPC_TYPE = 0x500000000L;
    public static final long NPC_MULTI = 0x410000000L;
    public static final long NPC_MULTI_TYPE = 0x510000000L;
    public static final long NPC_MULTI_CHILDREN = 0x520000000L;
    public static final long HEADBAR_NODE = 0x420000000L;
    public static final long HEADBAR = 0x430000000L;
    public static final long HEADBAR_TYPE = 0x440000000L;
    public static final long HEADBAR_UPDATE_NODE = 0x450000000L;
    public static final long HEADBAR_UPDATE = 0x460000000L;
    public static final long PLAYER = 0x600000000L;
    public static final long PLAYER_NAME = 0x610000000L;
    public static final long BUCKETS_NPC = 0x700000000L;
    public static final long NODE_NPC = 0x710000000L;
    public static final long BUCKETS_PLAYER = 0x720000000L;
    public static final long NODE_PLAYER = 0x730000000L;
    public static final long NPC_INDICES = 0x740000000L;
    public static final long VARPS = 0x800000000L;
    public static final long VARP_BITS = 0x808000000L;
    public static final long VARC_STORE = 0x810000000L;
    public static final long VARC_ENTRIES = 0x820000000L;
    public static final long VARC_STRING_HEAP = 0x830000000L;
    public static final long FOLLOWER_VARPS = 0x840000000L;
    public static final long FRIEND_MANAGER = 0x850000000L;
    public static final long NAME_LISTS = 0x860000000L;
    public static final long INV_BUCKETS = 0x900000000L;
    public static final long INV_NODES = 0x910000000L;
    public static final long ARCHIVE = 0xa00000000L;
    public static final long RENDERER = 0xb00000000L;
    public static final long MAP_LOADER = 0xb10000000L;
    public static final long COLLISION = 0xb20000000L;
    public static final long LOC_HEAP = 0xb30000000L;
    public static final long PILES = 0xb40000000L;
    public static final long TILE_POOL = 0xb50000000L;
    public static final long IF_STORE = 0xc00000000L;
    public static final long IF_GROUPS = 0xc10000000L;
    public static final long IF_SLOTS = 0xc20000000L;
    public static final long IF_TYPES = 0xc30000000L;
    public static final long IF_OPEN = 0xc40000000L;
    public static final long IF_HEAP = 0xc50000000L;
    public static final long IF_SUB = 0xc60000000L;
    public static final long PROJECTILES = 0xb60000000L;
    public static final long GRAPHICS_OBJECTS = 0xb70000000L;
    public static final long KEYED_PROJECTILES = 0xb80000000L;
    /**
     * Piles, nodes and items {@link #setPile} adds are bump-allocated from here.
     */
    private static final long PILE_HEAP = 0xb44000000L;
    /**
     * Archive tables and file buffers are bump-allocated from here.
     */
    private static final long ARCHIVE_HEAP = 0xa10000000L;

    public static final int VARP_COUNT = 64;
    public static final int SCENE_SIZE = 8;
    public static final int CYCLE = 150;
    /**
     * Loc id of the ground decoration on tile (2, 3), and its height offset (LocType opcode 96).
     */
    public static final int DECOR_LOC = 1234;
    public static final int DECOR_OFFSET = 10;

    /**
     * The map's (and the world's) base tile.
     */
    public static final int BASE_X = 3200;
    public static final int BASE_Z = 3200;

    /**
     * Loc ids: a door (wall, 1×2, ops "Open", which reads "Close" while varp 10 is 50-150, and a tagged "Knock"), an
     * oak (2×2 game object), a plain wall and a lamp (wall decoration).
     */
    public static final int DOOR_LOC = 1500;
    public static final int TREE_LOC = 1600;
    public static final int WALL_LOC = 1700;
    public static final int LAMP_LOC = 1800;

    /**
     * Collision grids start one tile outside the scene and are 2 tiles wider, as in the client.
     */
    public static final int COLLISION_ORIGIN = -1;
    public static final int COLLISION_SIZE = 10;
    public static final int COLLISION_STRIDE = 0x28;

    /**
     * The flags of the scenery line across x = 4 on level 0 (blocks walking and sight).
     */
    public static final int SCENERY_FLAGS = 0x20100;

    /**
     * Inventory slot arrays are allocated this big; an inventory's vectors use the first {@code length} slots.
     */
    private static final int INV_CAPACITY = 32;
    private static final int INV_NODE = 0x1000;
    private static final int CONFIG_GROUPS = 16;

    /**
     * Interfaces: groups in the store, the frame counter components are stamped with when drawn, and 162:2's text.
     */
    public static final int IF_GROUP_COUNT = 200;
    public static final int RENDER_CYCLE = 500;
    public static final String LONG_TEXT = "A line of chat long enough for the heap";

    /**
     * Spot anim ids of the two projectiles in flight: the spell (first in the list) and the arrow.
     */
    public static final int SPELL_PROJECTILE = 91;
    public static final int ARROW_PROJECTILE = 10;

    /**
     * Spot anim id of the keyed projectile, and the key the server sent it with.
     */
    public static final int KEYED_PROJECTILE = 1481;
    public static final int PROJECTILE_KEY = 77;

    /**
     * Spot anim ids of the two graphics objects: the splash (first in the list) and the smoke.
     */
    public static final int SPLASH_GRAPHIC = 85;
    public static final int SMOKE_GRAPHIC = 53;

    /**
     * Where the graphics objects' SeqTypes sit; the fixture only stores the pointers.
     */
    private static final long GRAPHIC_SEQ_TYPE = 0xb7f000000L;

    public final Mapping map;
    public final FakeMemory memory = new FakeMemory();
    private final Map<Integer, Long> invNodes = new HashMap<>();
    private final boolean[] npcLoaded = {true, true};
    private long locHeap = LOC_HEAP;
    private long tilePool = TILE_POOL;
    private long ifTypes = IF_TYPES;
    private long ifSlots = IF_SLOTS;
    private final Map<Integer, TreeMap<Integer, byte[]>> configFiles = new TreeMap<>();
    private long archiveHeap = ARCHIVE_HEAP;
    private long pileHeap = PILE_HEAP;

    public GameFixture() throws IOException {
        map = seedMapping();
        memory.setModuleBase(MODULE_BASE);
        memory.alloc(PILE_HEAP, 0x10000);
        buildClient();
        buildWorld();
        buildScene();
        buildActors();
        buildStats();
        buildVars();
        buildFriends();
        buildInventories();
        buildConfigs();
        buildView();
        buildLocs();
        buildPiles();
        buildCollision();
        buildInterfaces();
        buildProjectiles();
        buildKeyedProjectiles();
        buildGraphicsObjects();
        buildMenu();
    }

    public void setGameState(int state) {
        memory.putInt(CLIENT + map.offset("Client.gameState"), state);
    }

    public void setLoginToken(long token) {
        memory.putLong(CLIENT + map.offset("Client.loginToken"), token);
    }

    public void setTick(int tick) {
        memory.putInt(CLIENT + map.offset("Client.tickCount"), tick);
    }

    /**
     * Sets the client's cycle counter (the {@code cycle} global), which starts at {@link #CYCLE}.
     *
     * @param cycle the client cycle
     */
    public void setCycle(int cycle) {
        memory.putInt(MODULE_BASE + map.global("cycle"), cycle);
    }

    public void setStatsSent(boolean sent) {
        memory.putInt(CLIENT + map.offset("Client.statsValidGate"), sent ? 1 : 0);
    }

    public void setStat(int id, int xp, int level, int base) {
        memory.putInt(CLIENT + map.offset("Client.skillXp") + id * 4L, xp);
        memory.putInt(CLIENT + map.offset("Client.skillLevel") + id * 4L, level);
        memory.putInt(CLIENT + map.offset("Client.skillBase") + id * 4L, base);
    }

    /**
     * Sets a varp the way the server does: both the server copy ({@code getVarp}) and the copy varbits read.
     *
     * @param id    the varp
     * @param value its new value in both copies
     */
    public void setVarp(int id, int value) {
        memory.putInt(VARPS + id * 4L, value);
        memory.putInt(VARP_BITS + id * 4L, value);
    }

    /**
     * Changes only the copy varbits read, as a client-side script would.
     *
     * @param id    the varp
     * @param value its new value in the varbit copy only
     */
    public void setVarpBits(int id, int value) {
        memory.putInt(VARP_BITS + id * 4L, value);
    }

    public void setWorldFlags(int flags) {
        memory.putInt(CLIENT + map.offset("Client.worldFlags"), flags);
    }

    /**
     * Sets a slot of an existing inventory (93, 94 or 95), growing its vectors to cover it like the client does.
     *
     * @param inv   the inventory id (93, 94 or 95)
     * @param slot  the slot to set
     * @param item  the item id, -1 for empty
     * @param count the stack size
     */
    public void setInvSlot(int inv, int slot, int item, int count) {
        long node = invNodes.get(inv);
        long ids = memory.readLong(node + map.offset("Inventory.ids"));
        long counts = memory.readLong(node + map.offset("Inventory.counts"));
        int length = (int) ((memory.readLong(node + map.offset("Inventory.idsEnd")) - ids) / 4);
        for (int s = length; s <= slot; s++) {
            memory.putInt(ids + s * 4L, -1);
            memory.putInt(counts + s * 4L, 0);
        }
        if (slot >= length) {
            memory.putLong(node + map.offset("Inventory.idsEnd"), ids + (slot + 1) * 4L);
            memory.putLong(node + map.offset("Inventory.countsEnd"), counts + (slot + 1) * 4L);
        }
        memory.putInt(ids + slot * 4L, item);
        memory.putInt(counts + slot * 4L, count);
    }

    /**
     * Adds or replaces a config file and lays the archive out again. Make new readers afterwards: they cache.
     *
     * @param group the config group (see {@code ConfigTypes})
     * @param id    the file id within the group
     * @param data  the file's bytes, e.g. from {@link ConfigFile}
     */
    public void putConfigFile(int group, int id, byte[] data) {
        configFiles.computeIfAbsent(group, g -> new TreeMap<>()).put(id, data);
        writeArchive();
    }

    /**
     * A corner height of the scene (level, corner x, corner z), in the client's units (more negative is higher).
     *
     * @param level  the scene level (plane)
     * @param x      the corner x, 0 to the scene size
     * @param z      the corner z, 0 to the scene size
     * @param height the height there
     */
    public void setCornerHeight(int level, int x, int z, int height) {
        long index = ((long) level * (SCENE_SIZE + 1) + x) * (SCENE_SIZE + 1) + z;
        memory.putInt(HEIGHTS + index * 4, height);
    }

    /**
     * A tile's settings byte (bit 2 marks a tile under a bridge, read on level 1).
     *
     * @param level    the scene level (plane)
     * @param x        the tile x
     * @param z        the tile z
     * @param settings the settings byte
     */
    public void setTileSettings(int level, int x, int z, int settings) {
        memory.putByte(SETTINGS + ((long) level * SCENE_SIZE + x) * SCENE_SIZE + z, settings);
    }

    /**
     * Moves the map's base, as a map rebuild does (the world's base stays).
     *
     * @param x the new base x
     * @param z the new base z
     */
    public void setMapBase(int x, int z) {
        memory.putInt(MAP_LOADER + map.offset("MapLoader.baseX"), x);
        memory.putInt(MAP_LOADER + map.offset("MapLoader.baseZ"), z);
    }

    /**
     * Resizes the client and its window.
     *
     * @param width        the client's width in its own pixels
     * @param height       the client's height
     * @param screenWidth  the window's width in physical pixels
     * @param screenHeight the window's height
     */
    public void setClientSize(int width, int height, int screenWidth, int screenHeight) {
        memory.putInt(RENDERER + map.offset("Renderer.canvasWidth"), width);
        memory.putInt(RENDERER + map.offset("Renderer.canvasHeight"), height);
        memory.putInt(RENDERER + map.offset("Renderer.screenWidth"), screenWidth);
        memory.putInt(RENDERER + map.offset("Renderer.screenHeight"), screenHeight);
    }

    /**
     * Points the camera: its eye, and the sines and cosines the scene keeps for the frame.
     *
     * @param x        the eye's fine x
     * @param y        the eye's height
     * @param z        the eye's fine z
     * @param sinPitch sine of the pitch
     * @param cosPitch cosine of the pitch
     * @param sinYaw   sine of the yaw
     * @param cosYaw   cosine of the yaw
     */
    public void setCamera(int x, int y, int z, float sinPitch, float cosPitch, float sinYaw, float cosYaw) {
        long pos = CLIENT + map.offset("Client.camera.pos");
        memory.putInt(pos, x);
        memory.putInt(pos + 4, y);
        memory.putInt(pos + 8, z);
        long trig = SCENE + map.offset("Scene.cameraTrig");
        memory.putInt(trig, Float.floatToRawIntBits(sinPitch));
        memory.putInt(trig + 4, Float.floatToRawIntBits(cosPitch));
        memory.putInt(trig + 8, Float.floatToRawIntBits(sinYaw));
        memory.putInt(trig + 12, Float.floatToRawIntBits(cosYaw));
    }

    /**
     * Takes the first item off a pile, as when it is picked up.
     *
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     */
    public void removeFirstPileItem(int level, int x, int z) {
        long sentinel = pileSentinel(level, x, z);
        int next = map.container("list.node.next");
        long first = memory.readLong(sentinel + next);
        memory.putLong(sentinel + next, memory.readLong(first + next));
    }

    /**
     * Puts a wall on a tile, replacing any wall there, as a server loc change does.
     *
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @param id    the wall's loc id, or -1 to remove the wall
     */
    public void setWall(int level, int x, int z, int id) {
        memory.putLong(tile(level, x, z) + map.offset("Tile.wall"), id < 0 ? 0 : loc(id));
    }

    /**
     * Takes every game object off a tile, as when a tree is felled and its object removed.
     *
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     */
    public void removeGameObjects(int level, int x, int z) {
        memory.putInt(tile(level, x, z) + map.offset("Tile.gameObjectCount"), 0);
    }

    /**
     * Lays a new pile on a tile, replacing its list (the old nodes stay where they were), and adds the pile grid's
     * column and rows for it if the grid has none.
     *
     * @param level  the level
     * @param x      the tile x
     * @param z      the tile z
     * @param ids    the items' ids, in pile order; empty for an empty pile
     * @param counts their stack sizes
     */
    public void setPile(int level, int x, int z, int[] ids, int[] counts) {
        int stride = map.offset("World.objPiles.stride");
        int data = map.offset("World.objPiles.dataOffset");
        int next = map.container("list.node.next");
        int prev = map.container("list.node.prev");
        int value = map.container("list.node.value");
        long levels = memory.readLong(WORLD + map.offset("World.objPiles"));
        long columnSlot = levels + (long) level * stride + data;
        if (memory.readLong(columnSlot) == 0) {
            memory.putLong(columnSlot, pileAlloc(SCENE_SIZE * stride));
        }
        long rowSlot = memory.readLong(columnSlot) + (long) x * stride + data;
        if (memory.readLong(rowSlot) == 0) {
            long rows = pileAlloc(SCENE_SIZE * stride);
            memory.putLong(rowSlot, rows);
            for (int row = 0; row < SCENE_SIZE; row++) {
                long sentinel = rows + (long) row * stride;
                memory.putLong(sentinel + next, sentinel);
                memory.putLong(sentinel + prev, sentinel);
            }
        }
        long sentinel = memory.readLong(rowSlot) + (long) z * stride;
        long previous = sentinel;
        for (int i = 0; i < ids.length; i++) {
            long node = pileAlloc(0x40);
            long item = pileAlloc(0x40);
            memory.putInt(item + map.offset("GroundItem.id"), ids[i]);
            memory.putInt(item + map.offset("GroundItem.count"), counts[i]);
            memory.putLong(node + value, item);
            memory.putLong(previous + next, node);
            previous = node;
        }
        memory.putLong(previous + next, sentinel);
    }

    /**
     * Changes the size of one stack in a pile, as when more of a stackable item is dropped on it.
     *
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @param index the stack's position in the pile, from 0
     * @param count its new size
     */
    public void setPileCount(int level, int x, int z, int index, int count) {
        int next = map.container("list.node.next");
        long node = memory.readLong(pileSentinel(level, x, z) + next);
        for (int i = 0; i < index; i++) {
            node = memory.readLong(node + next);
        }
        long item = memory.readLong(node + map.container("list.node.value"));
        memory.putInt(item + map.offset("GroundItem.count"), count);
    }

    private long pileAlloc(int size) {
        long at = pileHeap;
        pileHeap += (size + 0xf) & ~0xfL;
        return at;
    }

    /**
     * Points an actor at a target, as an interaction does.
     *
     * @param address the NPC's or player's address, e.g. {@link #NPC}
     * @param type    the target type (0 none, 1 NPC, 2 player)
     * @param index   the target's index, -1 for none
     */
    public void setInteracting(long address, int type, int index) {
        memory.putByte(address + map.offset("Actor.interactingType"), type);
        memory.putInt(address + map.offset("Actor.interacting"), index);
    }

    /**
     * Sets the clipping rectangle of an open group (149 or 162).
     *
     * @param group the group
     * @param x1    the left edge
     * @param y1    the top edge
     * @param x2    the right edge
     * @param y2    the bottom edge
     */
    public void setGroupClip(int group, int x1, int y1, int x2, int y2) {
        long node = memory.readLong(IF_OPEN + 6 * 8);
        while (memory.readInt(node + map.offset("IfGroupNode.key")) != group) {
            node = memory.readLong(node + map.offset("IfGroupNode.next"));
        }
        long coords = node + map.offset("IfGroupNode.value");
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipX1"), x1);
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipY1"), y1);
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipX2"), x2);
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipY2"), y2);
    }

    /**
     * Relinks the projectile list with some of the two projectiles, as when one is launched or lands.
     *
     * @param indices 0 for the spell, 1 for the arrow, in list order; none for an empty list
     */
    public void setProjectilesInFlight(int... indices) {
        int next = map.container("list.node.next");
        int prev = map.container("list.node.prev");
        long sentinel = CLIENT + map.offset("Client.projectiles");
        long previous = sentinel;
        for (int index : indices) {
            long node = PROJECTILES + 0x20L * index;
            memory.putLong(previous + next, node);
            memory.putLong(node + prev, previous);
            previous = node;
        }
        memory.putLong(previous + next, sentinel);
        memory.putLong(sentinel + prev, previous);
    }

    /**
     * Sets a tile's collision flags.
     *
     * @param level the level
     * @param x     the scene tile x
     * @param z     the scene tile z
     * @param flags the flags
     */
    public void setCollision(int level, int x, int z, int flags) {
        memory.putInt(collisionCell(level, x, z), flags);
    }

    /**
     * Hides or shows one projectile, as {@code renderProjectiles} does for all of them.
     *
     * @param index  0 for the spell, 1 for the arrow
     * @param hidden whether the client should hide it
     */
    public void setProjectileHidden(int index, boolean hidden) {
        memory.putByte(projectile(index) + map.offset("Projectile.hidden"), hidden ? 1 : 0);
    }

    /**
     * Empties the projectile list, as when everything in flight has landed.
     */
    public void clearProjectiles() {
        long sentinel = CLIENT + map.offset("Client.projectiles");
        memory.putLong(sentinel + map.container("list.node.next"), sentinel);
        memory.putLong(sentinel + map.container("list.node.prev"), sentinel);
    }

    /**
     * Loads or unloads NPC 5 or 6, as a spawn or despawn does: the world's NPC table and active list are relinked with
     * or without it. Its memory stays where it was, as freed memory might.
     *
     * @param index  5 or 6
     * @param loaded whether the NPC should be in the world
     */
    public void setNpcLoaded(int index, boolean loaded) {
        npcLoaded[index - 5] = loaded;
        long previous = 0;
        int count = 0;
        memory.putLong(BUCKETS_NPC, 0);
        for (int i = 0; i < npcLoaded.length; i++) {
            if (!npcLoaded[i]) {
                continue;
            }
            long node = NODE_NPC + i * 0x20L;
            memory.putLong(node + map.container("hash_map.node.next"), 0);
            memory.putLong(previous == 0 ? BUCKETS_NPC : previous + map.container("hash_map.node.next"), node);
            memory.putInt(NPC_INDICES + count * 4L, 5 + i);
            previous = node;
            count++;
        }
        memory.putInt(WORLD + map.offset("World.npcIndexCount"), count);
    }

    /**
     * Changes the cycle NPC 5 or 6 was created on, as when the client puts a new NPC where the old one was.
     *
     * @param index 5 or 6
     * @param cycle the new creation cycle
     */
    public void setNpcSpawnCycle(int index, int cycle) {
        memory.putInt((index == 5 ? NPC : NPC_MULTI) + map.offset("Npc.spawnCycle"), cycle);
    }

    /**
     * Renames a player, as when the client puts a new player where the old one was.
     *
     * @param index      7, 9 or 10
     * @param display    the new display name
     * @param normalized the new normalised name
     */
    public void setPlayerName(int index, String display, String normalized) {
        long name = memory.readLong(player(index) + map.offset("Player.name"));
        memory.putSsoString(name + map.offset("PlayerName.display"), display);
        memory.putSsoString(name + map.offset("PlayerName.normalized"), normalized);
    }

    /**
     * Rewrites the client's player index list, as players loading or leaving do. The players' memory and the world's
     * player table stay as they were.
     *
     * @param indices the loaded players' indices, from 7, 9 and 10
     */
    public void setPlayersLoaded(int... indices) {
        memory.putInt(CLIENT + map.offset("Client.playerIndexCount"), indices.length);
        for (int i = 0; i < indices.length; i++) {
            memory.putInt(CLIENT + map.offset("Client.playerIndices") + i * 4L, indices[i]);
        }
    }

    /**
     * Moves an actor to another scene tile.
     *
     * @param address the NPC's or player's address, e.g. {@link #NPC}
     * @param x       the scene x
     * @param z       the scene z
     */
    public void setActorTile(long address, int x, int z) {
        memory.putInt(address + map.offset("Actor.pathX"), x);
        memory.putInt(address + map.offset("Actor.pathZ"), z);
    }

    private void buildClient() {
        memory.alloc(MODULE_BASE + map.global("client"), 8);
        memory.alloc(CLIENT, 0x420000);
        memory.putLong(MODULE_BASE + map.global("client"), CLIENT);
        memory.alloc(MODULE_BASE + map.global("cycle"), 4);
        memory.putInt(MODULE_BASE + map.global("cycle"), CYCLE);
        setGameState(30);
        setLoginToken(0x1234);
        setTick(123);
        memory.putLong(CLIENT + map.offset("Client.topLevelWorld"), WORLD);
        memory.putInt(CLIENT + map.offset("Client.localPlayerIndex"), 7);
        memory.putInt(CLIENT + map.offset("Client.localPlayerWorldId"), 0);
        memory.putInt(CLIENT + map.offset("Client.playerIndexCount"), 3);
        memory.putInt(CLIENT + map.offset("Client.playerIndices"), 7);
        memory.putInt(CLIENT + map.offset("Client.playerIndices") + 4, 9);
        memory.putInt(CLIENT + map.offset("Client.playerIndices") + 8, 10);

        memory.putLong(CLIENT + map.offset("Client.renderer"), RENDERER);
        memory.putLong(CLIENT + map.offset("Client.mapLoader"), MAP_LOADER);
        memory.alloc(MODULE_BASE + map.global("cameraControlled"), 1);
        memory.alloc(MODULE_BASE + map.global("cameraTarget"), 4);
        memory.putInt(MODULE_BASE + map.global("cameraTarget"), -1);

        // The worlds table: world id 0 is the top-level world
        long worlds = CLIENT + map.offset("Client.worlds.map");
        memory.putLong(worlds + map.container("hash_map.buckets"), WORLDS_BUCKETS);
        memory.putLong(worlds + map.container("hash_map.bucketCount"), 1);
        memory.alloc(WORLDS_BUCKETS, 8);
        memory.putLong(WORLDS_BUCKETS, WORLDS_NODE);
        memory.alloc(WORLDS_NODE, 0x20);
        memory.putInt(WORLDS_NODE + map.container("hash_map.node.key"), 0);
        memory.putLong(WORLDS_NODE + map.container("hash_map.node.value"), WORLD);
    }

    private void buildWorld() {
        memory.alloc(WORLD, 0x200);
        memory.putInt(WORLD + map.offset("World.plane"), 0);
        memory.putInt(WORLD + map.offset("World.baseX"), BASE_X);
        memory.putInt(WORLD + map.offset("World.baseZ"), BASE_Z);
        memory.putInt(WORLD + map.offset("World.width"), SCENE_SIZE);
        memory.putInt(WORLD + map.offset("World.height"), SCENE_SIZE);
        memory.putLong(WORLD + map.offset("World.scene"), SCENE);

        hashMap(WORLD + map.offset("World.players"), BUCKETS_PLAYER, NODE_PLAYER,
                new int[]{7, 9, 10}, new long[]{PLAYER, player(9), player(10)});
        hashMap(WORLD + map.offset("World.npcs"), BUCKETS_NPC, NODE_NPC, new int[]{5, 6}, new long[]{NPC, NPC_MULTI});

        memory.alloc(NPC_INDICES, 4 * 8);
        memory.putLong(WORLD + map.offset("World.npcIndices"), NPC_INDICES);
        memory.putInt(WORLD + map.offset("World.npcIndexCount"), 2);
        memory.putInt(NPC_INDICES, 5);
        memory.putInt(NPC_INDICES + 4, 6);
    }

    /**
     * A one-bucket hash_map whose chain holds {@code keys} → {@code values} in order.
     *
     * @param map_    address of the hash_map object
     * @param buckets where to put its single bucket
     * @param nodes   where to put its nodes (0x20 bytes each)
     * @param keys    the node keys, in chain order
     * @param values  the value stored with each key
     */
    private void hashMap(long map_, long buckets, long nodes, int[] keys, long[] values) {
        memory.putLong(map_ + map.container("hash_map.buckets"), buckets);
        memory.putLong(map_ + map.container("hash_map.bucketCount"), 1);
        memory.alloc(buckets, 8);
        memory.putLong(buckets, nodes);
        memory.alloc(nodes, keys.length * 0x20);
        for (int i = 0; i < keys.length; i++) {
            long node = nodes + i * 0x20L;
            memory.putInt(node + map.container("hash_map.node.key"), keys[i]);
            memory.putLong(node + map.container("hash_map.node.value"), values[i]);
            memory.putLong(node + map.container("hash_map.node.next"), i + 1 < keys.length ? node + 0x20 : 0);
        }
    }

    /**
     * An 8×8 scene, 4 levels, flat at height 0, with a ground decoration on tile (2, 3) of level 0.
     */
    private void buildScene() {
        int levels = 4;
        memory.alloc(SCENE, 0x1000);
        memory.putInt(SCENE + map.offset("Scene.sizeX"), SCENE_SIZE);
        memory.putInt(SCENE + map.offset("Scene.sizeZ"), SCENE_SIZE);
        memory.putInt(SCENE + map.offset("Scene.levels"), levels);
        memory.alloc(HEIGHTS, levels * (SCENE_SIZE + 1) * (SCENE_SIZE + 1) * 4);
        memory.putLong(SCENE + map.offset("Scene.heights"), HEIGHTS);
        memory.alloc(SETTINGS, levels * SCENE_SIZE * SCENE_SIZE);
        memory.putLong(SCENE + map.offset("Scene.tileSettings"), SETTINGS);
        int stride = map.offset("Scene.tileEntryStride");
        memory.alloc(TILES, levels * SCENE_SIZE * SCENE_SIZE * stride);
        memory.putLong(SCENE + map.offset("Scene.tiles"), TILES);

        memory.alloc(TILE, 0x200);
        memory.alloc(DECOR, 0x100);
        memory.putLong(TILES + (2L * SCENE_SIZE + 3) * stride + map.offset("Scene.tileEntryPtrOffset"), TILE);
        memory.putLong(TILE + map.offset("Tile.groundDecor"), DECOR);
        memory.putLong(DECOR + map.offset("Loc.tag"), (long) DECOR_LOC << map.offset("Loc.tagIdShift"));
    }

    private void buildActors() {
        // NPC 5: own name left empty so npcName() falls back to the type name
        memory.alloc(NPC, 0x740);
        memory.putInt(NPC + map.offset("Actor.pathX"), 3222);
        memory.putInt(NPC + map.offset("Actor.pathZ"), 3218);
        memory.putSsoString(NPC + map.offset("Actor.name"), "");
        memory.putLong(NPC + map.offset("Npc.type"), NPC_TYPE);
        memory.putInt(NPC + map.offset("Npc.index"), 5);
        memory.putInt(NPC + map.offset("Npc.spawnCycle"), 1000);
        memory.putByte(NPC + map.offset("Actor.hidden"), 1);
        memory.putByte(NPC + map.offset("Actor.render2D"), 1);
        memory.putByte(NPC + map.offset("Actor.interactingType"), 2);
        memory.putInt(NPC + map.offset("Actor.interacting"), 7);
        buildHeadbar(NPC);
        memory.alloc(NPC_TYPE, 0x200);
        memory.putInt(NPC_TYPE + map.offset("NpcType.id"), 3029);
        memory.putSsoString(NPC_TYPE + map.offset("NpcType.name"), "Goblin");
        memory.putInt(NPC_TYPE + map.offset("NpcType.type"), 311);
        memory.putInt(NPC_TYPE + map.offset("NpcType.multiVarbit"), -1);
        memory.putInt(NPC_TYPE + map.offset("NpcType.multiVarp"), -1);

        // NPC 6: a multi-NPC whose base type has no name of its own; varbit 2 picks the child type
        memory.alloc(NPC_MULTI, 0x740);
        memory.putInt(NPC_MULTI + map.offset("Actor.pathX"), 3223);
        memory.putInt(NPC_MULTI + map.offset("Actor.pathZ"), 3218);
        memory.putSsoString(NPC_MULTI + map.offset("Actor.name"), "");
        memory.putByte(NPC_MULTI + map.offset("Actor.render2D"), 1);
        memory.putLong(NPC_MULTI + map.offset("Npc.type"), NPC_MULTI_TYPE);
        memory.putInt(NPC_MULTI + map.offset("Npc.index"), 6);
        memory.alloc(NPC_MULTI_TYPE, 0x200);
        memory.putInt(NPC_MULTI_TYPE + map.offset("NpcType.id"), 3100);
        memory.putSsoString(NPC_MULTI_TYPE + map.offset("NpcType.name"), "null");
        memory.putByte(NPC_MULTI_TYPE + map.offset("NpcType.isFollower"), 1);
        memory.putLong(NPC_MULTI_TYPE + map.offset("NpcType.multiCount"), 3);
        memory.putLong(NPC_MULTI_TYPE + map.offset("NpcType.multi"), NPC_MULTI_CHILDREN);
        memory.putInt(NPC_MULTI_TYPE + map.offset("NpcType.multiVarbit"), 2);
        memory.putInt(NPC_MULTI_TYPE + map.offset("NpcType.multiVarp"), -1);
        memory.alloc(NPC_MULTI_CHILDREN, 12);
        memory.putInt(NPC_MULTI_CHILDREN, 3029);
        memory.putInt(NPC_MULTI_CHILDREN + 4, 3030);
        memory.putInt(NPC_MULTI_CHILDREN + 8, -1);

        // Player 7, Zezima: name pair behind a pointer at Player+0x718
        buildPlayer(PLAYER, PLAYER_NAME, 7, "Zezima", "zezima", 3221, 3218);
        memory.putInt(PLAYER + map.offset("Actor.pathLength"), 3);
        memory.putInt(PLAYER + map.offset("Actor.fineX"), 2 * 128 + 32);
        memory.putInt(PLAYER + map.offset("Actor.fineZ"), 3 * 128 + 96);
        buildPlayer(player(9), player(9) + 0x1000, 9, "Bob", "bob", 3225, 3218);
        buildPlayer(player(10), player(10) + 0x1000, 10, "Troll", "troll", 3226, 3218);
        memory.putInt(player(9) + map.offset("Player.friendState"), 0); // not worked out: found in the friend list
        memory.putInt(player(10) + map.offset("Player.friendState"), 2);
    }

    private static long player(int index) {
        return index == 7 ? PLAYER : PLAYER + index * 0x10000L;
    }

    private void buildPlayer(long player, long name, int index, String display, String normalized, int x, int z) {
        memory.alloc(player, 0x7b0);
        memory.putInt(player + map.offset("Actor.pathX"), x);
        memory.putInt(player + map.offset("Actor.pathZ"), z);
        memory.putByte(player + map.offset("Actor.render2D"), 1);
        memory.putInt(player + map.offset("Player.index"), index);
        memory.putInt(player + map.offset("Player.level"), 0);
        memory.alloc(name, 0x30);
        memory.putLong(player + map.offset("Player.name"), name);
        memory.putSsoString(name + map.offset("PlayerName.display"), display);
        memory.putSsoString(name + map.offset("PlayerName.normalized"), normalized);
    }

    /**
     * One health bar (head bar type 0, 300 cycles) with one update at cycle 100 showing 0/0: current at cycle 150, so
     * the bar is rendered and empty.
     *
     * @param actor the NPC or player to give the bar to
     */
    private void buildHeadbar(long actor) {
        int next = map.container("list.node.next");
        int prev = map.container("list.node.prev");
        int value = map.container("list.node.value");
        long sentinel = actor + map.offset("Actor.headbars");
        memory.putLong(sentinel + next, HEADBAR_NODE);
        memory.putLong(sentinel + prev, HEADBAR_NODE);
        memory.putLong(actor + map.offset("Actor.headbarCount"), 1);
        memory.alloc(HEADBAR_NODE, 0x20);
        memory.putLong(HEADBAR_NODE + next, sentinel);
        memory.putLong(HEADBAR_NODE + prev, sentinel);
        memory.putLong(HEADBAR_NODE + value, HEADBAR);

        memory.alloc(HEADBAR, 0x40);
        memory.putLong(HEADBAR + map.offset("Headbar.type"), HEADBAR_TYPE);
        long updates = HEADBAR + map.offset("Headbar.updates");
        memory.putLong(updates + next, HEADBAR_UPDATE_NODE);
        memory.putLong(updates + prev, HEADBAR_UPDATE_NODE);
        memory.putLong(HEADBAR + map.offset("Headbar.updateCount"), 1);
        memory.alloc(HEADBAR_TYPE, 0x40);
        memory.putInt(HEADBAR_TYPE + map.offset("HeadbarType.id"), 0);
        memory.putInt(HEADBAR_TYPE + map.offset("HeadbarType.duration"), 300);
        memory.alloc(HEADBAR_UPDATE_NODE, 0x20);
        memory.putLong(HEADBAR_UPDATE_NODE + next, updates);
        memory.putLong(HEADBAR_UPDATE_NODE + prev, updates);
        memory.putLong(HEADBAR_UPDATE_NODE + value, HEADBAR_UPDATE);
        memory.alloc(HEADBAR_UPDATE, 0x10);
        memory.putInt(HEADBAR_UPDATE + map.offset("HeadbarEntry.cycle"), 100);
        memory.putInt(HEADBAR_UPDATE + map.offset("HeadbarEntry.health"), 0);
        memory.putInt(HEADBAR_UPDATE + map.offset("HeadbarEntry.health2"), 0);
        memory.putInt(HEADBAR_UPDATE + map.offset("HeadbarEntry.cycleOffset"), 0);
    }

    /**
     * Attack 99, Hitpoints 10 boosted to 12; the rest level 1.
     */
    private void buildStats() {
        setStatsSent(true);
        int count = map.offset("Client.skillIndexMax");
        for (int id = 0; id < count; id++) {
            setStat(id, 0, 1, 1);
        }
        setStat(0, 13_034_431, 99, 99);
        setStat(3, 1_154, 12, 10);
    }

    /**
     * 64 varps (varp n = n * 10, except varp 12 = 6, the follower slot); varcs 10 = 42, 20 = "hello", 30 = -5; world
     * flags 0 (a free world).
     */
    private void buildVars() {
        memory.alloc(MODULE_BASE + map.global("varps"), 8);
        memory.alloc(VARPS, VARP_COUNT * 4);
        memory.putLong(MODULE_BASE + map.global("varps"), VARPS);
        memory.alloc(MODULE_BASE + map.global("varpsBits"), 8);
        memory.alloc(VARP_BITS, VARP_COUNT * 4);
        memory.putLong(MODULE_BASE + map.global("varpsBits"), VARP_BITS);
        memory.alloc(MODULE_BASE + map.global("varpCount"), 4);
        memory.putInt(MODULE_BASE + map.global("varpCount"), VARP_COUNT);
        for (int id = 0; id < VARP_COUNT; id++) {
            setVarp(id, id * 10);
        }
        setVarp(12, 6);

        memory.alloc(FOLLOWER_VARPS, 4);
        memory.putInt(FOLLOWER_VARPS, 12);
        memory.putLong(CLIENT + map.offset("Client.followerVarps"), FOLLOWER_VARPS);
        memory.putLong(CLIENT + map.offset("Client.followerVarpsEnd"), FOLLOWER_VARPS + 4);

        int stride = map.offset("VarcEntry.size");
        memory.putLong(CLIENT + map.offset("Client.varcs"), VARC_STORE);
        memory.alloc(VARC_STORE, 0x100);
        memory.alloc(VARC_ENTRIES, 3 * stride);
        memory.putLong(VARC_STORE + map.offset("Varcs.entries"), VARC_ENTRIES);
        memory.putLong(VARC_STORE + map.offset("Varcs.entriesEnd"), VARC_ENTRIES + 3L * stride);
        putIntVarc(VARC_ENTRIES, 10, 42);
        putStringVarc(VARC_ENTRIES + stride, 20, "hello");
        putIntVarc(VARC_ENTRIES + 2L * stride, 30, -5);

        setWorldFlags(0);
    }

    private void putIntVarc(long entry, int id, int value) {
        memory.putInt(entry + map.offset("VarcEntry.key"), id);
        memory.putInt(entry + map.offset("VarcEntry.value"), value);
        memory.putByte(entry + map.offset("VarcEntry.type"), 1);
    }

    private void putStringVarc(long entry, int id, String value) {
        memory.putInt(entry + map.offset("VarcEntry.key"), id);
        memory.putSsoString(entry + map.offset("VarcEntry.value"), value);
        memory.putByte(entry + map.offset("VarcEntry.type"), 2);
    }

    /**
     * The friend list holds Bob (online on world 301); the ignore list holds Troll.
     */
    private void buildFriends() {
        memory.alloc(FRIEND_MANAGER, 0x200);
        memory.putLong(CLIENT + map.offset("Client.friendManager"), FRIEND_MANAGER);
        memory.alloc(NAME_LISTS, 0x1000);
        long friend = NAME_LISTS + 0x800;
        memory.putInt(friend + map.offset("Friend.world"), 301);
        nameList(FRIEND_MANAGER + map.offset("FriendManager.friends"), NAME_LISTS, "Bob", "bob", friend);
        nameList(FRIEND_MANAGER + map.offset("FriendManager.ignores"), NAME_LISTS + 0x100, "Troll", "troll", 0);
    }

    /**
     * A name list whose current-names table holds one node; its previous-names table is empty.
     *
     * @param list       address of the name list (embedded in the friend manager)
     * @param space      scratch memory for the table's bucket and node
     * @param display    the name as shown
     * @param normalized the name the list is keyed by
     * @param value      the node's value (a Friend for the friend list, 0 otherwise)
     */
    private void nameList(long list, long space, String display, String normalized, long value) {
        long buckets = space;
        long node = space + 0x10;
        long table = list + map.offset("NameList.byName");
        memory.putLong(table + map.container("hash_map.buckets"), buckets);
        memory.putLong(table + map.container("hash_map.bucketCount"), 1);
        memory.putLong(buckets, node);
        memory.putSsoString(node + map.offset("PlayerName.display"), display);
        memory.putSsoString(node + map.offset("PlayerName.normalized"), normalized);
        memory.putLong(node + map.offset("NameNode.value"), value);
        memory.putLong(node + map.offset("NameNode.next"), 0);
    }

    /**
     * Two buckets (plus the end sentinel): 94 alone in bucket 0, 93 then 95 chained in bucket 1. The backpack holds
     * logs (1511) in slot 0, coins (995) in slots 1 and 3, slot 2 empty; worn has a weapon in slot 3; the bank is
     * empty.
     */
    private void buildInventories() {
        memory.alloc(MODULE_BASE + map.global("inventories.buckets"), 8);
        memory.alloc(MODULE_BASE + map.global("inventories.bucketCount"), 8);
        memory.putLong(MODULE_BASE + map.global("inventories.buckets"), INV_BUCKETS);
        memory.putLong(MODULE_BASE + map.global("inventories.bucketCount"), 2);
        memory.alloc(INV_BUCKETS, 3 * 8);
        memory.putLong(INV_BUCKETS + 2 * 8, 0xFFFF_FFFF_FFFF_FFFFL); // sentinel, never followed

        long n94 = addInventory(94, 0);
        long n93 = addInventory(93, 1);
        long n95 = addInventory(95, 2);
        memory.putLong(INV_BUCKETS, n94);
        memory.putLong(INV_BUCKETS + 8, n93);
        memory.putLong(n93 + map.offset("Inventory.next"), n95);

        setInvSlot(93, 0, 1511, 1);
        setInvSlot(93, 1, 995, 100);
        setInvSlot(93, 3, 995, 50);
        setInvSlot(94, 3, 4151, 1);
    }

    private long addInventory(int id, int index) {
        long node = INV_NODES + (long) index * INV_NODE;
        long ids = node + 0x100;
        long counts = ids + INV_CAPACITY * 4;
        memory.alloc(node, INV_NODE);
        memory.putInt(node + map.offset("Inventory.id"), id);
        memory.putLong(node + map.offset("Inventory.ids"), ids);
        memory.putLong(node + map.offset("Inventory.idsEnd"), ids);
        memory.putLong(node + map.offset("Inventory.counts"), counts);
        memory.putLong(node + map.offset("Inventory.countsEnd"), counts);
        invNodes.put(id, node);
        return node;
    }

    /**
     * Varbits 0 (varp 10 bits 0-3), 1 (varp 10 bits 4-7), 2 (varp 11 bit 0) and 4 (varp 12 bits 0-15), id 3 without a
     * file; inventories 93 (28 slots) and 94 (14); items 995 Coins (stacks), 1511 Logs (cost 4) and 4151 Abyssal whip
     * (members); NPC types 3029 Goblin and 3030 Goblin chief; loc 1234, a ground decoration raising the ground by 10.
     */
    private void buildConfigs() {
        memory.alloc(MODULE_BASE + map.global("configCache"), 8);
        memory.putLong(MODULE_BASE + map.global("configCache"), ARCHIVE);
        memory.alloc(ARCHIVE, 0x100);
        Map<Integer, byte[]> varbits = configFiles.computeIfAbsent(14, g -> new TreeMap<>());
        varbits.put(0, ConfigFile.varbit(10, 0, 3));
        varbits.put(1, ConfigFile.varbit(10, 4, 7));
        varbits.put(2, ConfigFile.varbit(11, 0, 0));
        varbits.put(4, ConfigFile.varbit(12, 0, 15));
        Map<Integer, byte[]> invs = configFiles.computeIfAbsent(5, g -> new TreeMap<>());
        invs.put(93, new ConfigFile().op(2).g2(28).end());
        invs.put(94, new ConfigFile().op(2).g2(14).end());
        Map<Integer, byte[]> objs = configFiles.computeIfAbsent(10, g -> new TreeMap<>());
        objs.put(995, new ConfigFile().op(2).str("Coins").op(11).op(12).g4(1).end());
        objs.put(1511, new ConfigFile().op(2).str("Logs").op(12).g4(4).op(32).str("Light").end());
        objs.put(4151, new ConfigFile().op(2).str("Abyssal whip").op(12).g4(120_001).op(16).end());
        Map<Integer, byte[]> npcs = configFiles.computeIfAbsent(9, g -> new TreeMap<>());
        npcs.put(3029, new ConfigFile().op(2).str("Goblin").op(18).g2(311).end());
        npcs.put(3030, new ConfigFile().op(2).str("Goblin chief").end());
        Map<Integer, byte[]> locs = configFiles.computeIfAbsent(6, g -> new TreeMap<>());
        locs.put(DECOR_LOC, new ConfigFile().op(2).str("Daisies").op(96).g1(DECOR_OFFSET).end());
        locs.put(DOOR_LOC, new ConfigFile().op(2).str("Door").op(15).g1(2).op(30).str("Open")
                .op(31).str("<col=ff0000>Knock</col><lt>3").op(101).g1(0).g2(10).g2(0xffff).g4(50).g4(150)
                .str("Close").end());
        locs.put(TREE_LOC, new ConfigFile().op(2).str("Oak").op(14).g1(2).op(15).g1(2).op(30).str("Chop down").end());
        locs.put(WALL_LOC, new ConfigFile().op(2).str("Wall").end());
        locs.put(LAMP_LOC, new ConfigFile().op(2).str("Lamp").end());
        writeArchive();
    }

    /**
     * Lays the archive's group and file tables out from {@link #configFiles} (fresh memory each time).
     */
    private void writeArchive() {
        int groupStride = map.offset("Js5Group.size");
        int fileStride = map.offset("Js5File.size");
        long groups = heap(CONFIG_GROUPS * groupStride);
        memory.putLong(ARCHIVE + map.offset("Js5Archive.groupCount"), CONFIG_GROUPS);
        memory.putLong(ARCHIVE + map.offset("Js5Archive.groups"), groups);
        for (int g = 0; g < CONFIG_GROUPS; g++) {
            long group = groups + (long) g * groupStride;
            TreeMap<Integer, byte[]> files = configFiles.get(g);
            if (files == null) {
                memory.putInt(group + map.offset("Js5Group.version"), -1);
                continue;
            }
            int count = files.lastKey() + 1;
            long table = heap(count * fileStride);
            memory.putInt(group + map.offset("Js5Group.version"), 1);
            memory.putLong(group + map.offset("Js5Group.fileCount"), count);
            memory.putLong(group + map.offset("Js5Group.files"), table);
            for (int id = 0; id < count; id++) {
                long entry = table + (long) id * fileStride;
                byte[] data = files.get(id);
                if (data == null) {
                    memory.putLong(entry + map.offset("Js5File.start"), -1);
                    continue;
                }
                long buffer = heap(0x20);
                long bytes = heap(data.length);
                memory.putBytes(bytes, data);
                memory.putLong(buffer + map.offset("Packet.length"), data.length);
                memory.putLong(buffer + map.offset("Packet.data"), bytes);
                memory.putLong(entry + map.offset("Js5File.start"), 0);
                memory.putLong(entry + map.offset("Js5File.buffer"), buffer);
            }
        }
    }

    /**
     * The camera 1000 above fine (0, 0) looking north along z (no pitch, no yaw), scale 512, zoom 600, angles
     * {pitch 4096, yaw 8192}, the look-at point ahead; the renderer and its viewport (centre 383, 251 in window
     * pixels); the map loader.
     */
    private void buildView() {
        memory.alloc(RENDERER, 0x200);
        setCamera(0, -1000, 0, 0f, 1f, 0f, 1f);
        long lookAt = CLIENT + map.offset("Client.camera.lookAt");
        memory.putInt(lookAt, 0);
        memory.putInt(lookAt + 4, -1000);
        memory.putInt(lookAt + 8, 1024);
        long angles = CLIENT + map.offset("Client.camera.angles");
        memory.putInt(angles, 4096);
        memory.putInt(angles + 4, 8192);
        memory.putInt(CLIENT + map.offset("Client.zoomA"), 512);
        memory.putInt(CLIENT + map.offset("Client.zoomB"), 600);

        setClientSize(765, 503, 1530, 1006);
        long viewport = RENDERER + map.offset("Renderer.viewport");
        memory.putInt(viewport + map.offset("Viewport.centerX"), 383);
        memory.putInt(viewport + map.offset("Viewport.centerY"), 251);

        memory.alloc(MAP_LOADER, 0x6000);
        setMapBase(BASE_X, BASE_Z);
    }

    /**
     * A scene tile, created if the tile table has none there yet.
     *
     * @param level the level
     * @param x     the tile x
     * @param z     the tile z
     * @return the tile's address
     */
    private long tile(int level, int x, int z) {
        int stride = map.offset("Scene.tileEntryStride");
        long entry = TILES + ((long) (level * SCENE_SIZE + x) * SCENE_SIZE + z) * stride
                + map.offset("Scene.tileEntryPtrOffset");
        long tile = memory.readLong(entry);
        if (tile == 0) {
            tile = tilePool;
            tilePool += 0x200;
            memory.alloc(tile, 0x200);
            memory.putLong(entry, tile);
        }
        return tile;
    }

    private long loc(int id) {
        long loc = locHeap;
        locHeap += 0x100;
        memory.alloc(loc, 0x100);
        memory.putLong(loc + map.offset("Loc.tag"), (long) id << map.offset("Loc.tagIdShift"));
        return loc;
    }

    private long gameObject(int id, int kind, int originX, int originZ) {
        long object = locHeap;
        locHeap += 0x300;
        memory.alloc(object, 0x300);
        memory.putLong(object + map.offset("GameObject.tag"),
                (long) id << map.offset("Loc.tagIdShift") | (long) kind << map.offset("Loc.tagKindShift"));
        memory.putInt(object + map.offset("GameObject.originX"), originX);
        memory.putInt(object + map.offset("GameObject.originZ"), originZ);
        return object;
    }

    private void putGameObjects(long tile, long... objects) {
        int stride = map.offset("Tile.gameObjectEntryStride");
        long array = locHeap;
        locHeap += 0x100;
        memory.alloc(array, objects.length * stride);
        for (int i = 0; i < objects.length; i++) {
            memory.putLong(array + (long) i * stride + 8, objects[i]);
        }
        memory.putInt(tile + map.offset("Tile.gameObjectCount"), objects.length);
        memory.putLong(tile + map.offset("Tile.gameObjects"), array);
    }

    private void buildLocs() {
        memory.putLong(tile(0, 1, 1) + map.offset("Tile.wall"), loc(DOOR_LOC));
        memory.putLong(tile(0, 4, 4) + map.offset("Tile.wall"), loc(WALL_LOC));
        long oak = gameObject(TREE_LOC, 2, 5, 5);
        long actor = gameObject(0, 0, 5, 5);
        putGameObjects(tile(0, 5, 5), actor, oak);
        putGameObjects(tile(0, 6, 5), oak);
        memory.putLong(tile(1, 6, 6) + map.offset("Tile.wallDecor"), loc(LAMP_LOC));
        setTileSettings(1, 6, 6, 2);
    }

    /**
     * The pile grid: level 0's columns and column 3's rows (empty lists); no other column has rows.
     */
    private void buildPiles() {
        int stride = map.offset("World.objPiles.stride");
        int data = map.offset("World.objPiles.dataOffset");
        long levels = PILES;
        long columns = PILES + 0x100;
        long rows = PILES + 0x400;
        memory.alloc(PILES, 0x2000);
        memory.putLong(WORLD + map.offset("World.objPiles"), levels);
        memory.putLong(levels + data, columns);
        memory.putLong(columns + 3L * stride + data, rows);
        for (int z = 0; z < SCENE_SIZE; z++) {
            long sentinel = rows + (long) z * stride;
            memory.putLong(sentinel + map.container("list.node.next"), sentinel);
            memory.putLong(sentinel + map.container("list.node.prev"), sentinel);
        }
        pile(0, 3, 3, new int[]{995, 1511, 995}, new int[]{100, 1, 5});
    }

    private long pileSentinel(int level, int x, int z) {
        int stride = map.offset("World.objPiles.stride");
        int data = map.offset("World.objPiles.dataOffset");
        long levels = memory.readLong(WORLD + map.offset("World.objPiles"));
        long columns = memory.readLong(levels + (long) level * stride + data);
        return memory.readLong(columns + (long) x * stride + data) + (long) z * stride;
    }

    private void pile(int level, int x, int z, int[] ids, int[] counts) {
        long sentinel = pileSentinel(level, x, z);
        int next = map.container("list.node.next");
        int value = map.container("list.node.value");
        long nodes = PILES + 0x1000;
        long items = PILES + 0x1400;
        long previous = sentinel;
        for (int i = 0; i < ids.length; i++) {
            long node = nodes + i * 0x40L;
            long item = items + i * 0x40L;
            memory.putInt(item + map.offset("GroundItem.id"), ids[i]);
            memory.putInt(item + map.offset("GroundItem.count"), counts[i]);
            memory.putLong(node + value, item);
            memory.putLong(previous + next, node);
            previous = node;
        }
        memory.putLong(previous + next, sentinel);
    }

    private long collisionCell(int level, int x, int z) {
        long record = COLLISION + (long) level * COLLISION_STRIDE;
        long columns = memory.readLong(record + map.offset("CollisionMapRecord.flags"));
        long column = memory.readLong(columns + (long) (x - COLLISION_ORIGIN) * map.offset("CollisionMapRecord.columnStride")
                + map.offset("CollisionMapRecord.columnData"));
        return column + (long) (z - COLLISION_ORIGIN) * 4;
    }

    /**
     * Four records (the vector the scene points at), each a 10×10 grid of columns; the ring around the scene reads as
     * unloaded.
     */
    private void buildCollision() {
        int levels = 4;
        int colStride = map.offset("CollisionMapRecord.columnStride");
        int colData = map.offset("CollisionMapRecord.columnData");
        long heap = COLLISION + 0x1000;
        memory.alloc(COLLISION, 0x40000);
        memory.putLong(SCENE + map.offset("Scene.collisionMaps"), COLLISION);
        memory.putLong(SCENE + map.offset("Scene.collisionMapsEnd"), COLLISION + (long) levels * COLLISION_STRIDE);
        for (int level = 0; level < levels; level++) {
            long record = COLLISION + (long) level * COLLISION_STRIDE;
            memory.putInt(record + map.offset("CollisionMapRecord.originX"), COLLISION_ORIGIN);
            memory.putInt(record + map.offset("CollisionMapRecord.originZ"), COLLISION_ORIGIN);
            memory.putInt(record + map.offset("CollisionMapRecord.sizeX"), COLLISION_SIZE);
            memory.putInt(record + map.offset("CollisionMapRecord.sizeZ"), COLLISION_SIZE);
            long columns = heap;
            heap += (long) COLLISION_SIZE * colStride;
            memory.putLong(record + map.offset("CollisionMapRecord.flags"), columns);
            for (int ix = 0; ix < COLLISION_SIZE; ix++) {
                long data = heap;
                heap += COLLISION_SIZE * 4L;
                memory.putLong(columns + (long) ix * colStride + colData, data);
                for (int iz = 0; iz < COLLISION_SIZE; iz++) {
                    boolean edge = ix == 0 || iz == 0 || ix == COLLISION_SIZE - 1 || iz == COLLISION_SIZE - 1;
                    memory.putInt(data + iz * 4L, edge ? 0x1000000 : 0);
                }
            }
        }
        for (int z = 1; z <= 6; z++) {
            setCollision(0, 4, z, SCENERY_FLAGS);
        }
    }

    private void buildInterfaces() {
        memory.alloc(IF_STORE, 0x7000);
        memory.putLong(CLIENT + map.offset("Client.interfaceStore"), IF_STORE);
        memory.putLong(IF_STORE + map.offset("IfStore.groupCount"), IF_GROUP_COUNT);
        memory.putLong(IF_STORE + map.offset("IfStore.groups"), IF_GROUPS);
        memory.alloc(IF_GROUPS, IF_GROUP_COUNT * map.offset("IfStoreGroup.stride"));
        memory.alloc(IF_SLOTS, 0x1000);
        memory.alloc(IF_HEAP, 0x100);
        memory.putInt(CLIENT + map.offset("Client.renderCycle"), RENDER_CYCLE);

        int inv = 149 << 16;
        long invRoot = component(inv, -1, -1, -1, WidgetType.LAYER, 0, 0, 190, 261);
        long item = component(inv, 0, -1, inv, WidgetType.GRAPHIC, 16, 8, 36, 32);
        memory.putInt(item + map.offset("IfType.linkObjType"), 995);
        memory.putInt(item + map.offset("IfType.linkObjCount"), 1000);
        memory.putSsoString(item + map.offset("IfType.baseOpName"), "Coins");
        ops(item, "Use", "", "Drop");
        long layer = component(inv, 1, -1, inv, WidgetType.LAYER, 58, 8, 36, 32);
        memory.putInt(layer + map.offset("IfType.layerIdXMin"), 2);
        memory.putInt(layer + map.offset("IfType.layerIdXMax"), 2);
        long nested = component(inv, 2, 1, inv, WidgetType.TEXT, 3, 4, 30, 12);
        memory.putSsoString(nested + map.offset("IfType.text"), "Nested");
        groupSlots(149, invRoot);
        dynamicSlots(invRoot, item, layer, nested);

        groupSlots(150, component(150 << 16, -1, -1, -1, WidgetType.LAYER, 0, 0, 10, 10));

        int chat = 162 << 16;
        long chatRoot = component(chat, -1, -1, -1, WidgetType.LAYER, 0, 0, 519, 165);
        long all = component(chat | 1, -1, -1, chat, WidgetType.TEXT, 5, 142, 56, 22);
        memory.putSsoString(all + map.offset("IfType.text"), "All");
        memory.putInt(all + map.offset("IfType.colour"), 0xffffff);
        memory.putInt(all + map.offset("IfType.renderCycle"), RENDER_CYCLE);
        ops(all, "Switch tab");
        long line = component(chat | 2, -1, -1, chat, WidgetType.TEXT, 0, 0, 500, 14);
        memory.putByte(line + map.offset("IfType.hidden"), 1);
        long text = line + map.offset("IfType.text");
        memory.putBytes(IF_HEAP, LONG_TEXT.getBytes(StandardCharsets.UTF_8));
        memory.putLong(text, IF_HEAP);
        memory.putLong(text + 8, LONG_TEXT.length());
        memory.putByte(text + map.container("string.heapFlagByte"), map.container("string.heapFlagMask"));
        groupSlots(162, chatRoot, all, line);

        // The toplevel: a root layer with two side panels where the inventory is drawn, the second hidden
        int top = 161 << 16;
        long topRoot = component(top, -1, -1, -1, WidgetType.LAYER, 0, 0, 1200, 800);
        long panel = component(top | 1, -1, -1, top, WidgetType.LAYER, 970, 328, 190, 261);
        long otherPanel = component(top | 2, -1, -1, top, WidgetType.LAYER, 970, 328, 190, 261);
        memory.putByte(otherPanel + map.offset("IfType.hidden"), 1);
        groupSlots(161, topRoot, panel, otherPanel);

        // The inventory and chatbox chain in bucket 6 of 13 (149 % 13 == 162 % 13), the toplevel is alone in bucket 5
        int buckets = 13;
        memory.alloc(IF_OPEN, 0x200);
        memory.putLong(CLIENT + map.offset("Client.interfaces.buckets"), IF_OPEN);
        memory.putInt(CLIENT + map.offset("Client.interfaces.bucketCount"), buckets);
        long first = IF_OPEN + 0x100;
        long second = first + 0x40;
        long third = second + 0x40;
        memory.putLong(IF_OPEN + 6 * 8, first);
        memory.putLong(IF_OPEN + 5 * 8, third);
        openGroup(first, 162, second, 0, 461, 2, 1);
        openGroup(second, 149, 0, 970, 328, 0, 0);
        openGroup(third, 161, 0, 0, 0, 0, 0);

        // The component table: the inventory is opened in the toplevel's first side panel; the chatbox in nothing
        int subBuckets = 7;
        memory.alloc(IF_SUB, 0x100);
        memory.putLong(CLIENT + map.offset("Client.subInterfaces.buckets"), IF_SUB);
        memory.putInt(CLIENT + map.offset("Client.subInterfaces.bucketCount"), subBuckets);
        long node = IF_SUB + 0x80;
        memory.putLong(IF_SUB + (long) Integer.remainderUnsigned(top | 1, subBuckets) * 8, node);
        memory.putInt(node + map.offset("SubInterfaceNode.key"), top | 1);
        memory.putInt(node + map.offset("SubInterfaceNode.group"), 149);
        memory.putLong(node + map.offset("SubInterfaceNode.next"), 0);
    }

    /**
     * Allocates an {@code IfType}: empty strings, never drawn, no item, ops or dynamic children.
     *
     * @param packedId   {@code group << 16 | component} (a dynamic component's root's)
     * @param subId      -1 for a static component
     * @param layerSubId sub id of a dynamic component's direct parent, -1 for its root
     * @param layerId    the parent's packed id, -1 for a top-level component
     * @param type       the {@code IfTypes} value
     * @param x          x relative to the parent
     * @param y          y relative to the parent
     * @param w          the width
     * @param h          the height
     * @return its address
     */
    private long component(int packedId, int subId, int layerSubId, int layerId, int type, int x, int y, int w, int h) {
        long c = ifTypes;
        ifTypes += 0x1000;
        memory.alloc(c, 0xc10);
        memory.putInt(c + map.offset("IfType.packedId"), packedId);
        memory.putInt(c + map.offset("IfType.subId"), subId);
        memory.putInt(c + map.offset("IfType.layerSubId"), layerSubId);
        memory.putInt(c + map.offset("IfType.layerId"), layerId);
        memory.putInt(c + map.offset("IfType.type"), type);
        memory.putInt(c + map.offset("IfType.x"), x);
        memory.putInt(c + map.offset("IfType.y"), y);
        memory.putInt(c + map.offset("IfType.width"), w);
        memory.putInt(c + map.offset("IfType.height"), h);
        memory.putInt(c + map.offset("IfType.layerIdXMin"), 0xffff);
        memory.putInt(c + map.offset("IfType.layerIdXMax"), 0);
        memory.putInt(c + map.offset("IfType.renderCycle"), -1);
        memory.putInt(c + map.offset("IfType.linkObjType"), -1);
        for (String field : new String[]{"sourceName", "graphicURL", "text", "text2", "baseOpName"}) {
            memory.putSsoString(c + map.offset("IfType." + field), "");
        }
        return c;
    }

    private void ops(long component, String... names) {
        int size = map.container("string.size");
        long array = ifSlots;
        ifSlots += (long) names.length * size;
        for (int i = 0; i < names.length; i++) {
            memory.putSsoString(array + (long) i * size, names[i]);
        }
        memory.putInt(component + map.offset("IfType.opCount"), names.length);
        memory.putLong(component + map.offset("IfType.opNames"), array);
    }

    private long slots(long... components) {
        int stride = map.offset("IfStoreGroup.slotStride");
        long array = ifSlots;
        ifSlots += (long) components.length * stride;
        for (int i = 0; i < components.length; i++) {
            memory.putLong(array + (long) i * stride + map.container("shared_ptr.ptr"), components[i]);
        }
        return array;
    }

    private void groupSlots(int group, long... components) {
        long entry = IF_GROUPS + (long) group * map.offset("IfStoreGroup.stride");
        memory.putLong(entry + map.offset("IfStoreGroup.count"), components.length);
        memory.putLong(entry + map.offset("IfStoreGroup.slots"), slots(components));
    }

    private void dynamicSlots(long root, long... children) {
        memory.putLong(root + map.offset("IfType.dynCount"), children.length);
        memory.putLong(root + map.offset("IfType.dynSlots"), slots(children));
    }

    private void openGroup(long node, int group, long next, int offX, int offY, int scriptOffX, int scriptOffY) {
        memory.putInt(node + map.offset("IfGroupNode.key"), group);
        memory.putLong(node + map.offset("IfGroupNode.next"), next);
        long coords = node + map.offset("IfGroupNode.value");
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipX1"), offX);
        memory.putInt(coords + map.offset("InterfaceCoordinates.clipY1"), offY);
        memory.putInt(coords + map.offset("InterfaceCoordinates.offX"), offX);
        memory.putInt(coords + map.offset("InterfaceCoordinates.offY"), offY);
        memory.putInt(coords + map.offset("InterfaceCoordinates.scriptOffX"), scriptOffX);
        memory.putInt(coords + map.offset("InterfaceCoordinates.scriptOffY"), scriptOffY);
    }

    private long heap(int size) {
        long at = archiveHeap;
        memory.alloc(at, Math.max(size, 1));
        archiveHeap += (Math.max(size, 1) + 0xf) & ~0xfL;
        return at;
    }

    /**
     * The projectile list, sentinel → spell → arrow → sentinel. The spell flies from player 7 (actor reference -8)
     * at NPC 5 (reference 6) between cycles 140 and 170 and has been placed; the arrow flies from tile (1, 1) to tile
     * (6, 6) (world tiles: the base added) between cycles 160 and 200, so at cycle {@link #CYCLE} it hasn't set off.
     */
    private void buildProjectiles() {
        int next = map.container("list.node.next");
        int prev = map.container("list.node.prev");
        int value = map.container("list.node.value");
        long sentinel = CLIENT + map.offset("Client.projectiles");
        long spellNode = PROJECTILES;
        long arrowNode = PROJECTILES + 0x20;
        memory.alloc(PROJECTILES, 0x40);
        memory.putLong(sentinel + next, spellNode);
        memory.putLong(sentinel + prev, arrowNode);
        memory.putLong(spellNode + next, arrowNode);
        memory.putLong(spellNode + prev, sentinel);
        memory.putLong(spellNode + value, projectile(0));
        memory.putLong(arrowNode + next, sentinel);
        memory.putLong(arrowNode + prev, spellNode);
        memory.putLong(arrowNode + value, projectile(1));

        long spell = projectile(0);
        memory.alloc(spell, 0x300);
        putProjectile(spell, SPELL_PROJECTILE, new int[]{0, BASE_X + 2, BASE_Z + 3, 43, -8},
                new int[]{0, BASE_X + 4, BASE_Z + 4, 31, 6}, 140, 170, 16, 64);
        memory.putByte(spell + map.offset("Projectile.started"), 1);
        memory.putLong(spell + map.offset("Projectile.fineX"), Double.doubleToRawLongBits(300.75));
        memory.putLong(spell + map.offset("Projectile.fineY"), Double.doubleToRawLongBits(-150.5));
        memory.putLong(spell + map.offset("Projectile.fineZ"), Double.doubleToRawLongBits(420.25));
        memory.putInt(spell + map.offset("Projectile.yaw"), 1536);
        memory.putInt(spell + map.offset("Projectile.pitch"), 100);

        long arrow = projectile(1);
        memory.alloc(arrow, 0x300);
        putProjectile(arrow, ARROW_PROJECTILE, new int[]{0, BASE_X + 1, BASE_Z + 1, 40, 0},
                new int[]{0, BASE_X + 6, BASE_Z + 6, 36, 0}, 160, 200, 15, 11);
        setProjectileHidden(1, true);
    }

    private static long projectile(int index) {
        return PROJECTILES + 0x1000L * (index + 1);
    }

    /**
     * The keyed projectile map: two buckets, the second holding the one projectile, key {@link #PROJECTILE_KEY}. It
     * flies from player 7 (actor reference -8) at NPC 5 (reference 6) between cycles 145 and 185 and has been placed;
     * its source is offset (16, 60, -16) and its target (8, 20, -8) in (x, height, z).
     */
    private void buildKeyedProjectiles() {
        long table = CLIENT + map.offset("Client.keyedProjectiles");
        long buckets = KEYED_PROJECTILES;
        long node = KEYED_PROJECTILES + 0x100;
        long p = keyedProjectile();
        memory.alloc(KEYED_PROJECTILES, 0x200);
        memory.alloc(p, 0x400);
        memory.putLong(table + map.container("hash_map.buckets"), buckets);
        memory.putLong(table + map.container("hash_map.bucketCount"), 2);
        memory.putInt(node + map.container("hash_map.node.key"), PROJECTILE_KEY);
        memory.putLong(node + map.container("hash_map.node.value"), p);
        memory.putLong(node + map.container("hash_map.node.next"), 0);
        setKeyedProjectileInFlight(true);

        memory.putInt(p + keyed("spotAnimId"), KEYED_PROJECTILE);
        putKeyedEndpoint(p, "source", BASE_X + 1, BASE_Z + 2, -8, new int[]{16, 60, -16});
        putKeyedEndpoint(p, "target", BASE_X + 4, BASE_Z + 4, 6, new int[]{8, 20, -8});
        memory.putInt(p + keyed("startCycle"), 145);
        memory.putInt(p + keyed("endCycle"), 185);
        memory.putByte(p + keyed("started"), 1);
        memory.putInt(p + keyed("fineX"), Float.floatToRawIntBits(250.5f));
        memory.putInt(p + keyed("fineY"), Float.floatToRawIntBits(-90.25f));
        memory.putInt(p + keyed("fineZ"), Float.floatToRawIntBits(330.75f));
        memory.putInt(p + keyed("yaw"), 1024);
        memory.putInt(p + keyed("pitch"), 32);
    }

    private static long keyedProjectile() {
        return KEYED_PROJECTILES + 0x1000;
    }

    private int keyed(String field) {
        return map.offset("KeyedProjectile." + field);
    }

    /**
     * Writes one end of the keyed projectile, on level 0.
     *
     * @param p      the projectile
     * @param side   "source" or "target"
     * @param x      the world tile x
     * @param z      the world tile z
     * @param actor  the actor reference
     * @param offset the x, height and z offset
     */
    private void putKeyedEndpoint(long p, String side, int x, int z, int actor, int[] offset) {
        memory.putInt(p + keyed(side + "Level"), 0);
        memory.putInt(p + keyed(side + "X"), x);
        memory.putInt(p + keyed(side + "Z"), z);
        memory.putInt(p + keyed(side + "Actor"), actor);
        memory.putInt(p + keyed(side + "OffsetX"), offset[0]);
        memory.putInt(p + keyed(side.equals("source") ? "startHeight" : "endHeight"), offset[1]);
        memory.putInt(p + keyed(side + "OffsetZ"), offset[2]);
    }

    /**
     * Files the keyed projectile in its bucket or takes it out, as when it is sent or lands.
     *
     * @param inFlight whether it is in the map
     */
    public void setKeyedProjectileInFlight(boolean inFlight) {
        memory.putLong(KEYED_PROJECTILES, 0);
        memory.putLong(KEYED_PROJECTILES + 8, inFlight ? KEYED_PROJECTILES + 0x100 : 0);
    }

    /**
     * Retargets the keyed projectile at a tile, as a retarget packet does.
     *
     * @param x the world tile x
     * @param z the world tile z
     */
    public void retargetKeyedProjectile(int x, int z) {
        putKeyedEndpoint(keyedProjectile(), "target", x, z, 0, new int[]{8, 20, -8});
    }

    /**
     * Moves the keyed projectile and pushes its cycles forward, which happens every cycle of its flight or while it is
     * held, without changing where it is going.
     */
    public void advanceKeyedProjectile() {
        long p = keyedProjectile();
        memory.putInt(p + keyed("fineX"), Float.floatToRawIntBits(260.5f));
        memory.putInt(p + keyed("startCycle"), 147);
        memory.putInt(p + keyed("endCycle"), 187);
    }

    /**
     * Gives the keyed projectile map a bucket count no read accepts, as a read racing the client's update might see it,
     * or puts the real one back.
     *
     * @param readable whether the map can be read
     */
    public void setKeyedProjectilesReadable(boolean readable) {
        memory.putLong(CLIENT + map.offset("Client.keyedProjectiles") + map.container("hash_map.bucketCount"),
                readable ? 2 : 1L << 40);
    }

    /**
     * The top-level world's graphics object list, sentinel → splash → smoke → sentinel. The splash plays on tile
     * (3, 4) from cycle 145 and shows frame 3; the smoke on tile (5, 2), 40 above the ground, starts on cycle 160, so
     * at cycle {@link #CYCLE} it hasn't.
     */
    private void buildGraphicsObjects() {
        memory.alloc(GRAPHICS_OBJECTS, 0x40);
        memory.alloc(GRAPHIC_SEQ_TYPE, 0x10);
        long splash = graphicsObject(0);
        memory.alloc(splash, 0x2c0);
        putGraphicsObject(splash, SPLASH_GRAPHIC, 3, 4, 0, 145);
        setGraphicsObjectFrame(0, 3);
        long smoke = graphicsObject(1);
        memory.alloc(smoke, 0x2c0);
        putGraphicsObject(smoke, SMOKE_GRAPHIC, 5, 2, -40, 160);
        setGraphicsObjectFrame(1, -1);
        setGraphicsObjectsPlaying(0, 1);
    }

    private static long graphicsObject(int index) {
        return GRAPHICS_OBJECTS + 0x1000L * (index + 1);
    }

    /**
     * Writes what the server sends for a graphics object, at the centre of a scene tile on level 0, with its animation
     * running.
     *
     * @param g          the graphics object
     * @param id         its spot anim id
     * @param x          the scene tile x
     * @param z          the scene tile z
     * @param height     the height it is drawn at
     * @param startCycle the cycle it starts playing on
     */
    private void putGraphicsObject(long g, int id, int x, int z, int height, int startCycle) {
        memory.putInt(g + map.offset("GraphicsObject.spotAnimId"), id);
        memory.putInt(g + map.offset("GraphicsObject.level"), 0);
        memory.putInt(g + map.offset("GraphicsObject.fineX"), x * 128 + 64);
        memory.putInt(g + map.offset("GraphicsObject.fineZ"), z * 128 + 64);
        memory.putInt(g + map.offset("GraphicsObject.height"), height);
        memory.putInt(g + map.offset("GraphicsObject.startCycle"), startCycle);
        memory.putLong(g + map.offset("GraphicsObject.animSeqType"), GRAPHIC_SEQ_TYPE);
    }

    /**
     * Relinks the top-level world's graphics object list with some of the two objects, as when one is played or
     * dropped.
     *
     * @param indices 0 for the splash, 1 for the smoke, in list order; none for an empty list
     */
    public void setGraphicsObjectsPlaying(int... indices) {
        int next = map.container("list.node.next");
        int prev = map.container("list.node.prev");
        int value = map.container("list.node.value");
        long sentinel = WORLD + map.offset("World.graphicsObjects");
        long previous = sentinel;
        for (int index : indices) {
            long node = GRAPHICS_OBJECTS + 0x20L * index;
            memory.putLong(node + value, graphicsObject(index));
            memory.putLong(previous + next, node);
            memory.putLong(node + prev, previous);
            previous = node;
        }
        memory.putLong(previous + next, sentinel);
        memory.putLong(sentinel + prev, previous);
    }

    /**
     * Ends or restarts a graphics object's animation: the client clears the SeqType pointer when it ends.
     *
     * @param index 0 for the splash, 1 for the smoke
     * @param ended whether the animation has ended
     */
    public void setGraphicsObjectEnded(int index, boolean ended) {
        memory.putLong(graphicsObject(index) + map.offset("GraphicsObject.animSeqType"), ended ? 0 : GRAPHIC_SEQ_TYPE);
    }

    /**
     * Moves a graphics object's animation to a frame.
     *
     * @param index 0 for the splash, 1 for the smoke
     * @param frame the frame, -1 before it has started
     */
    public void setGraphicsObjectFrame(int index, int frame) {
        memory.putShort(graphicsObject(index) + map.offset("GraphicsObject.animFrame"), frame);
    }

    /**
     * Gives a graphics object a new start cycle, as when the client frees one and plays another in its memory.
     *
     * @param index      0 for the splash, 1 for the smoke
     * @param startCycle the new start cycle
     */
    public void setGraphicsObjectStartCycle(int index, int startCycle) {
        memory.putInt(graphicsObject(index) + map.offset("GraphicsObject.startCycle"), startCycle);
    }

    /**
     * Breaks the graphics object list after its first node, as a read that races the client's update might see it.
     */
    public void breakGraphicsObjectList() {
        memory.putLong(GRAPHICS_OBJECTS + map.container("list.node.next"), 0);
    }

    /**
     * Writes what the server sends for a projectile.
     *
     * @param p          the projectile
     * @param id         its spot anim id
     * @param source     the source's level, world x, world z, height offset and actor reference
     * @param target     the target's, in the same order
     * @param startCycle the cycle it sets off on
     * @param endCycle   the cycle it lands on
     * @param slope      its initial climb
     * @param distance   how far from the source it appears
     */
    private void putProjectile(long p, int id, int[] source, int[] target, int startCycle, int endCycle, int slope,
                               int distance) {
        memory.putInt(p + map.offset("Projectile.spotAnimId"), id);
        String[] fields = {"Level", "X", "Z"};
        for (int i = 0; i < fields.length; i++) {
            memory.putInt(p + map.offset("Projectile.source" + fields[i]), source[i]);
            memory.putInt(p + map.offset("Projectile.target" + fields[i]), target[i]);
        }
        memory.putInt(p + map.offset("Projectile.startHeight"), source[3]);
        memory.putInt(p + map.offset("Projectile.sourceActor"), source[4]);
        memory.putInt(p + map.offset("Projectile.endHeight"), target[3]);
        memory.putInt(p + map.offset("Projectile.targetActor"), target[4]);
        memory.putInt(p + map.offset("Projectile.startCycle"), startCycle);
        memory.putInt(p + map.offset("Projectile.endCycle"), endCycle);
        memory.putInt(p + map.offset("Projectile.slope"), slope);
        memory.putInt(p + map.offset("Projectile.startDistance"), distance);
    }

    /**
     * The minimenu's entries, by the row they show in from the top. {@link #MENU_ATTACK} (NPC 5's second op) is the
     * left-click entry. Targets too long to sit inline (the NPC entries') sit in the entry's own buffer.
     */
    public static final int MENU_ATTACK = 0;
    public static final int MENU_CHOP = 1;
    public static final int MENU_TAKE = 2;
    public static final int MENU_DROP = 3;
    public static final int MENU_TRADE = 4;
    public static final int MENU_EXAMINE = 5;
    public static final int MENU_USE_ON_GOBLIN = 6;
    public static final int MENU_WALK = 7;
    public static final int MENU_CANCEL = 8;
    public static final int MENU_ROWS = 9;

    public static final long MENU = 0xb70000000L;
    private static final long MENU_LIST = 0xb71000000L;
    private static final long MENU_ENTRIES = 0xb72000000L;
    private static final long MENU_NODES = 0xb73000000L;
    private static final long MENU_CHILDREN = 0xb74000000L;

    /**
     * One native minimenu entry as the fixture writes it.
     *
     * @param option  the option text
     * @param target  the target text
     * @param subject the subject kind's name in {@code enums.minimenuSubject}, or null for none
     * @param op      the op index
     * @param typeId  the type id
     * @param index   the subject index
     * @param x       the world tile x
     * @param z       the world tile z
     */
    private record MenuSpec(String option, String target, String subject, int op, int typeId, int index, int x, int z) {
    }

    /**
     * Sets the menu's rows to plain rows showing these entries (top first).
     *
     * @param entries the entries, e.g. {@link #MENU_WALK}
     */
    public void setMenuRows(int... entries) {
        int nodeSize = map.container("minimenu.node.size");
        for (int i = 0; i < entries.length; i++) {
            putMenuNode(MENU_NODES + (long) i * nodeSize, MENU_LIST, entries[i], 0);
        }
        long rows = MENU + map.offset("Minimenu.rows");
        memory.putLong(rows, MENU_NODES);
        memory.putLong(rows + 8, MENU_NODES + (long) entries.length * nodeSize);
    }

    /**
     * Runs an entry, as a click does: copies it into the minimenu's last-run entry and zeroes the cycle count.
     *
     * @param entry the entry, e.g. {@link #MENU_CHOP}
     */
    public void clickMenuEntry(int entry) {
        putMenuEntry(MENU + map.offset("Minimenu.clicked"), menuSpecs().get(entry));
        setMenuClickCycles(0);
    }

    /**
     * Sets the cycles since an entry last ran, as the client cycle increments them.
     *
     * @param cycles the count
     */
    public void setMenuClickCycles(int cycles) {
        memory.putInt(MENU + map.offset("Minimenu.clickCycles"), cycles);
    }

    /**
     * Opens or closes the menu.
     *
     * @param open whether the menu is open
     */
    public void setMenuOpen(boolean open) {
        memory.putLong(MENU + map.offset("Minimenu.open"), open ? MENU + 0x140 : 0);
    }

    private List<MenuSpec> menuSpecs() {
        int examine = map.enumValue("minimenuOp", "EXAMINE", 0);
        int targeted = map.enumValue("minimenuOp", "TARGET", -1);
        return List.of(
                new MenuSpec("Attack", "<col=ffff00>Goblin<col=ff00>  (level-2)", "NPC", 2, 3029, 5, -1, -1),
                new MenuSpec("Chop down", "<col=00ffff>Oak", "LOC", 1, TREE_LOC, -1, BASE_X + 5, BASE_Z + 5),
                new MenuSpec("Take", "<col=ff9040>Coins", "OBJ", 3, 995, 100, BASE_X + 3, BASE_Z + 3),
                new MenuSpec("Drop", "<col=ff9040>Coins", "COMPONENT", 2, 149 << 16, 0, -1, -1),
                new MenuSpec("Trade with", "<col=ffffff>Bob", "PLAYER", 4, -1, 9, -1, -1),
                new MenuSpec("Examine", "<col=ffff00>Goblin<col=ff00>  (level-2)", "NPC", examine, 3029, 5, -1, -1),
                new MenuSpec("Use", "<col=ff9040>Coins<col=ffffff> -> <col=ffff00>Goblin", "NPC", targeted, 3029, 5,
                        -1, -1),
                new MenuSpec("Walk here", "", "TILE", Integer.MAX_VALUE, -1, -1, BASE_X + 2, BASE_Z + 3),
                new MenuSpec("Cancel", "", null, Integer.MAX_VALUE, -1, -1, -1, -1));
    }

    private void buildMenu() {
        int entrySize = map.offset("MinimenuEntry.size");
        int nodeSize = map.container("minimenu.node.size");
        memory.alloc(MENU, 0x900);
        memory.alloc(MENU_LIST, 0x40);
        memory.alloc(MENU_ENTRIES, MENU_ROWS * entrySize);
        memory.alloc(MENU_NODES, MENU_ROWS * nodeSize);
        memory.alloc(MENU_CHILDREN, 0x40);
        memory.putLong(CLIENT + map.offset("Client.minimenu"), MENU);
        memory.putLong(MENU_LIST + map.container("minimenu.list.entries"), MENU_ENTRIES);
        List<MenuSpec> specs = menuSpecs();
        for (int i = 0; i < specs.size(); i++) {
            putMenuEntry(MENU_ENTRIES + (long) i * entrySize, specs.get(i));
        }
        int[] rows = new int[MENU_ROWS];
        for (int i = 0; i < MENU_ROWS; i++) {
            rows[i] = i;
        }
        setMenuRows(rows);
        // The use-on row is a submenu group: no list, and a children vector holding the one node that shows the entry
        long child = MENU_CHILDREN + 0x20;
        putMenuNode(MENU_NODES + (long) MENU_USE_ON_GOBLIN * nodeSize, 0, 0, MENU_CHILDREN);
        memory.putLong(MENU_CHILDREN, child);
        memory.putLong(MENU_CHILDREN + 8, child + nodeSize);
        putMenuNode(child, MENU_LIST, MENU_USE_ON_GOBLIN, 0);
        putMenuEntry(MENU + map.offset("Minimenu.clicked"), specs.get(MENU_CANCEL));
        setMenuClickCycles(100);
        setMenuOpen(false);
    }

    private void putMenuNode(long node, long list, long index, long children) {
        memory.putLong(node + map.container("minimenu.node.list"), list);
        memory.putLong(node + map.container("minimenu.node.index"), index);
        memory.putLong(node + map.container("minimenu.node.children"), children);
    }

    private void putMenuEntry(long entry, MenuSpec spec) {
        putMenuString(entry, map.offset("MinimenuEntry.option"), spec.option());
        putMenuString(entry, map.offset("MinimenuEntry.target"), spec.target());
        int kind = spec.subject() == null ? 0 : map.enumValue("minimenuSubject", spec.subject(), -1);
        memory.putByte(entry + map.offset("MinimenuEntry.subjectType"), kind);
        memory.putInt(entry + map.offset("MinimenuEntry.opIndex"), spec.op());
        memory.putInt(entry + map.offset("MinimenuEntry.typeId"), spec.typeId());
        memory.putInt(entry + map.offset("MinimenuEntry.subjectIndex"), spec.index());
        memory.putInt(entry + map.offset("MinimenuEntry.level"), 0);
        memory.putInt(entry + map.offset("MinimenuEntry.x"), spec.x());
        memory.putInt(entry + map.offset("MinimenuEntry.z"), spec.z());
    }

    /**
     * Writes a minimenu string: inline when it fits, else in heap form pointing at a buffer inside the entry right
     * after the string object, as the client's fixed-capacity strings do.
     *
     * @param entry  the entry
     * @param offset where the string object sits in it
     * @param text   the text
     */
    private void putMenuString(long entry, int offset, String text) {
        long string = entry + offset;
        memory.putBytes(string, new byte[0x18]);
        if (text.length() < map.container("string.ssoCapacity")) {
            memory.putSsoString(string, text);
            return;
        }
        long buffer = string + 0x28;
        memory.putBytes(buffer, text.getBytes(StandardCharsets.UTF_8));
        memory.putLong(string, buffer);
        memory.putLong(string + 8, text.length());
        memory.putByte(string + map.container("string.heapFlagByte"), map.container("string.heapFlagMask"));
    }
}
