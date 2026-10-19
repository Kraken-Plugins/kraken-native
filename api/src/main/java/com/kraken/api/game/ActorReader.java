package com.kraken.api.game;

import com.kraken.api.config.ConfigTypes;
import com.kraken.api.config.LocType;
import com.kraken.api.config.NpcType;
import com.kraken.api.config.ObjType;
import com.kraken.api.config.VarBitType;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;

/**
 * Reads players and NPCs: finding one by index, and everything the {@code ClientOp} actor functions and the
 * {@code osrs.Entity}/{@code Npc}/{@code Player} methods read from it. {@link Client} and the {@link Npc}/{@link Player}
 * handles share one instance. Each method mirrors a native handler; where it differs, its Javadoc says so.
 *
 * <p>Two ways of finding an actor, as in the client: the {@code ClientOp} functions ({@code npcName(id)} and friends)
 * search every loaded world (the main one and sub-worlds such as boats), while the Lua objects ({@link Npc},
 * {@link Player}) only look in the top-level world.
 *
 * <p>Keys added to the mapping after the first cut are optional here: without them, just the methods that need them
 * return their "missing" values.
 */
final class ActorReader extends GameReader {

    /** Highest NPC index {@code getNpcObj} accepts. */
    static final int MAX_NPC_INDEX = 0xffff;
    /** Highest player index {@code getPlayerObj} accepts. */
    static final int MAX_PLAYER_INDEX = 0x7ff;
    /** Highest NPC index the {@code ClientOp} NPC functions accept (their lookup's bound). */
    private static final int MAX_NPC_LOOKUP = 0x10000;
    /** Highest player index the {@code ClientOp} player functions accept. */
    private static final int MAX_PLAYER_LOOKUP = 0x800;

    private static final int MAX_LIST = 64;          // headbars per actor, updates per headbar
    private static final int MAX_FOLLOWER_VARPS = 64;
    private static final int MAX_WORLDS = 256;
    private static final int MAX_INDICES = 65536;
    private static final long MAX_BUCKETS = 1 << 16;
    private static final int MAX_CHAIN = 10_000;

    final ConfigTypes configs;

    // Client and World
    private final int cTopWorld;
    private final int cWorldsMap;
    private final int cLocalIndex;
    private final int cLocalWorldId;
    private final int wScene;
    private final int wPlane;
    private final int wPlayers;
    private final int wNpcs;
    private final int wNpcIndices;
    private final int wNpcCount;

    // Scene (ground heights)
    private final int scSizeX;
    private final int scSizeZ;
    private final int scLevels;
    private final int scTiles;
    private final int scTileStride;
    private final int scTilePtr;
    private final int scHeights;
    private final int scSettings;
    private final int tileGroundDecor;
    private final int locTag;
    private final int locTagShift;

    // hash_map nodes
    private final int nodeKey;
    private final int nodeValue;
    private final int nodeNext;

    // Actor
    private final int aWorldId;
    private final int aFineX;
    private final int aFineZ;
    private final int aPathLength;
    private final int aPathX;
    private final int aPathZ;
    private final int aName;            // the NPC's own inline name (Actor.name)
    private final int aInteractingType;
    private final int aInteracting;
    private final int aHeadbars;        // eastl::list sentinel
    private final int aHeadbarCount;
    private final int aHidden;          // nonzero = model hidden (render3D(false))
    private final int aRender2D;        // nonzero = 2D elements drawn

    // Npc and NpcType (the native struct the NPC points at)
    private final int npcType;
    private final int npcIndex;
    private final int npcSpawnCycle;
    private final int ntId;
    private final int ntName;
    private final int ntCategory;
    private final int ntIsFollower;
    private final int ntMultiCount;
    private final int ntMulti;
    private final int ntMultiVarbit;
    private final int ntMultiVarp;

    // Player
    private final int pName;            // pointer to the name pair {display, normalised}
    private final int pnCanonical;      // the normalised name in that pair
    private final int pLevel;
    private final int pIndex;
    private final int pFriendState;
    private final int pFriendChatState;
    private final int pGroupState;

    // Headbars
    private final long cycleRva;
    private final int listNext;
    private final int listPrev;
    private final int listValue;
    private final int hbType;
    private final int hbUpdates;
    private final int hbUpdateCount;
    private final int hbtId;
    private final int hbtDuration;
    private final int hueCycle;
    private final int hueHealth;
    private final int hueHealth2;
    private final int hueCycleOffset;

    // World flags, followers and the varp array varbits read
    private final int cWorldFlags;
    private final int cFollowerVarps;
    private final int cFollowerVarpsEnd;
    private final long varpsBitsRva;

    // Friends, ignores and friends chat
    private final int cFriendManager;
    private final int fmFriends;
    private final int fmIgnores;
    private final int cFriendsChat;
    private final int nlByName;
    private final int nlByPrevName;
    private final int nameNodeValue;
    private final int nameNodeNext;
    private final int friendWorld;
    private final int mapBuckets;
    private final int mapBucketCount;

    ActorReader(Memory memory, Mapping mapping, ConfigTypes configs) {
        super(memory, mapping);
        this.configs = configs;

        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cWorldsMap = optionalOffset("Client.worlds.map");
        this.cLocalIndex = mapping.offset("Client.localPlayerIndex");
        this.cLocalWorldId = optionalOffset("Client.localPlayerWorldId");
        this.wScene = optionalOffset("World.scene");
        this.wPlane = mapping.offset("World.plane");
        this.wPlayers = mapping.offset("World.players");
        this.wNpcs = mapping.offset("World.npcs");
        this.wNpcIndices = mapping.offset("World.npcIndices");
        this.wNpcCount = mapping.offset("World.npcIndexCount");

        this.scSizeX = optionalOffset("Scene.sizeX");
        this.scSizeZ = optionalOffset("Scene.sizeZ");
        this.scLevels = optionalOffset("Scene.levels");
        this.scTiles = optionalOffset("Scene.tiles");
        this.scTileStride = optionalOffset("Scene.tileEntryStride");
        this.scTilePtr = optionalOffset("Scene.tileEntryPtrOffset");
        this.scHeights = optionalOffset("Scene.heights");
        this.scSettings = optionalOffset("Scene.tileSettings");
        this.tileGroundDecor = optionalOffset("Tile.groundDecor");
        this.locTag = optionalOffset("Loc.tag");
        this.locTagShift = optionalOffset("Loc.tagIdShift");

        this.nodeKey = mapping.container("hash_map.node.key");
        this.nodeValue = mapping.container("hash_map.node.value");
        this.nodeNext = mapping.container("hash_map.node.next");

        this.aWorldId = optionalOffset("Actor.worldId");
        this.aFineX = optionalOffset("Actor.fineX");
        this.aFineZ = optionalOffset("Actor.fineZ");
        this.aPathLength = optionalOffset("Actor.pathLength");
        this.aPathX = mapping.offset("Actor.pathX");
        this.aPathZ = mapping.offset("Actor.pathZ");
        this.aName = mapping.offset("Actor.name");
        this.aInteractingType = mapping.has("Actor.interactingType") ? mapping.offset("Actor.interactingType")
                : optionalOffset("Actor.interactingValid");
        this.aInteracting = optionalOffset("Actor.interacting");
        this.aHeadbars = optionalOffset("Actor.headbars");
        this.aHeadbarCount = optionalOffset("Actor.headbarCount");
        this.aHidden = optionalOffset("Actor.hidden");
        this.aRender2D = optionalOffset("Actor.render2D");

        this.npcType = mapping.offset("Npc.type");
        this.npcIndex = mapping.has("Npc.index") ? mapping.offset("Npc.index") : optionalOffset("Npc.uidLow");
        this.npcSpawnCycle = optionalOffset("Npc.spawnCycle");
        this.ntId = mapping.offset("NpcType.id");
        this.ntName = mapping.offset("NpcType.name");
        this.ntCategory = optionalOffset("NpcType.type");
        this.ntIsFollower = optionalOffset("NpcType.isFollower");
        this.ntMultiCount = optionalOffset("NpcType.multiCount");
        this.ntMulti = optionalOffset("NpcType.multi");
        this.ntMultiVarbit = optionalOffset("NpcType.multiVarbit");
        this.ntMultiVarp = optionalOffset("NpcType.multiVarp");

        this.pName = mapping.offsetOr("Player.name", 0x718);
        this.pnCanonical = optionalOffset("PlayerName.normalized");
        this.pLevel = optionalOffset("Player.level");
        this.pIndex = optionalOffset("Player.index");
        this.pFriendState = optionalOffset("Player.friendState");
        this.pFriendChatState = optionalOffset("Player.friendChatState");
        this.pGroupState = optionalOffset("Player.groupState");

        this.cycleRva = mapping.hasGlobal("cycle") ? mapping.global("cycle") : -1;
        this.listNext = containerOr("list.node.next", 0);
        this.listPrev = containerOr("list.node.prev", 8);
        this.listValue = containerOr("list.node.value", 0x18);
        this.hbType = optionalOffset("Headbar.type");
        this.hbUpdates = optionalOffset("Headbar.updates");
        this.hbUpdateCount = optionalOffset("Headbar.updateCount");
        this.hbtId = optionalOffset("HeadbarType.id");
        this.hbtDuration = optionalOffset("HeadbarType.duration");
        this.hueCycle = optionalOffset("HeadbarEntry.cycle");
        this.hueHealth = mapping.has("HeadbarEntry.health") ? mapping.offset("HeadbarEntry.health")
                : optionalOffset("HeadbarEntry.a");
        this.hueHealth2 = mapping.has("HeadbarEntry.health2") ? mapping.offset("HeadbarEntry.health2")
                : optionalOffset("HeadbarEntry.b");
        this.hueCycleOffset = optionalOffset("HeadbarEntry.cycleOffset");

        this.cWorldFlags = optionalOffset("Client.worldFlags");
        this.cFollowerVarps = optionalOffset("Client.followerVarps");
        this.cFollowerVarpsEnd = optionalOffset("Client.followerVarpsEnd");
        this.varpsBitsRva = mapping.hasGlobal("varpsBits") ? mapping.global("varpsBits") : -1;

        this.cFriendManager = optionalOffset("Client.friendManager");
        this.fmFriends = optionalOffset("FriendManager.friends");
        this.fmIgnores = optionalOffset("FriendManager.ignores");
        this.cFriendsChat = optionalOffset("Client.friendsChat");
        this.nlByName = optionalOffset("NameList.byName");
        this.nlByPrevName = optionalOffset("NameList.byPrevName");
        this.nameNodeValue = optionalOffset("NameNode.value");
        this.nameNodeNext = optionalOffset("NameNode.next");
        this.friendWorld = optionalOffset("Friend.world");
        this.mapBuckets = mapping.container("hash_map.buckets");
        this.mapBucketCount = mapping.container("hash_map.bucketCount");
    }

    long topWorld() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cTopWorld);
    }

    /**
     * Every loaded world (the main one and sub-worlds), in the order the client's lookups walk them. Just the top-level
     * world if the mapping doesn't have the worlds table or it can't be read.
     */
    long[] worlds() {
        long client = clientBase();
        if (client == 0) {
            return new long[0];
        }
        long[] worlds = new long[MAX_WORLDS];
        int[] n = {0};
        if (cWorldsMap >= 0) {
            forEachNode(client + cWorldsMap, (key, world) -> {
                if (world != 0 && n[0] < MAX_WORLDS) {
                    worlds[n[0]++] = world;
                }
                return false;
            });
        }
        if (n[0] == 0) {
            long top = topWorld();
            return top == 0 ? new long[0] : new long[]{top};
        }
        return java.util.Arrays.copyOf(worlds, n[0]);
    }

    /** The NPC at {@code index} in the top-level world, or 0. What the Lua {@code Npc} object resolves to. */
    long npc(int index) {
        if (index < 0 || index > MAX_NPC_INDEX) {
            return 0;
        }
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wNpcs, index);
    }

    /** The player at {@code index} in the top-level world, or 0. What the Lua {@code Player} object resolves to. */
    long player(int index) {
        if (index < 0 || index > MAX_PLAYER_INDEX) {
            return 0;
        }
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wPlayers, index);
    }

    /** The NPC at {@code index} in the first world that has one, or 0: the {@code ClientOp} NPC functions' lookup. */
    long npcInAnyWorld(int index) {
        if (index < 0 || index > MAX_NPC_LOOKUP) {
            return 0;
        }
        for (long world : worlds()) {
            long npc = structs.hashGet(world + wNpcs, index);
            if (npc != 0) {
                return npc;
            }
        }
        return 0;
    }

    /** The player at {@code index} in the first world that has one, or 0: the {@code ClientOp} player lookup. */
    long playerInAnyWorld(int index) {
        if (index < 0 || index > MAX_PLAYER_LOOKUP) {
            return 0;
        }
        for (long world : worlds()) {
            long player = structs.hashGet(world + wPlayers, index);
            if (player != 0) {
                return player;
            }
        }
        return 0;
    }

    /** The world an actor is in: the top-level world for world id 0, otherwise that sub-world (0 if not loaded). */
    long actorWorld(long actor) {
        if (actor == 0) {
            return 0;
        }
        int id = aWorldId < 0 ? 0 : safeInt(actor + aWorldId, 0);
        if (id == 0) {
            return topWorld();
        }
        long client = clientBase();
        return client == 0 || cWorldsMap < 0 ? 0 : structs.hashGet(client + cWorldsMap, id);
    }

    int localPlayerIndex() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cLocalIndex, -1);
    }

    /** The local player, found the way {@code coord()} finds it: in the world the client says it's in. 0 if none. */
    long localPlayer() {
        long client = clientBase();
        if (client == 0) {
            return 0;
        }
        long world;
        if (cLocalWorldId >= 0 && cWorldsMap >= 0) {
            int id = safeInt(client + cLocalWorldId, 0);
            world = structs.hashGet(client + cWorldsMap, id);
            if (world == 0 && id == 0) {
                world = topWorld();
            }
        } else {
            world = topWorld();
        }
        return world == 0 ? 0 : structs.hashGet(world + wPlayers, localPlayerIndex());
    }

    /** The actor's tile, on its world's plane ({@code npcCoord}/{@code playerCoord}), or null. */
    MapCoord coord(long actor) {
        if (actor == 0) {
            return null;
        }
        try {
            long world = actorWorld(actor);
            int plane = world == 0 ? 0 : memory.readInt(world + wPlane);
            return new MapCoord(plane, memory.readInt(actor + aPathX), memory.readInt(actor + aPathZ));
        } catch (MemoryException e) {
            return null;
        }
    }

    /** {@code coord()}: the local player's tile, on the player's own plane field. Null if there's no local player. */
    MapCoord localCoord() {
        long player = localPlayer();
        if (player == 0) {
            return null;
        }
        try {
            int plane = pLevel >= 0 ? memory.readInt(player + pLevel) : memory.readInt(actorWorld(player) + wPlane);
            return new MapCoord(plane, memory.readInt(player + aPathX), memory.readInt(player + aPathZ));
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * {@code npcCoordFine}/{@code playerCoordFine}: the actor's fine position with the ground height at it on its
     * world's plane; {@link FineCoord#MISSING} if the actor or its world isn't loaded.
     */
    FineCoord fineCoord(long actor) {
        long world = actorWorld(actor);
        return world == 0 ? FineCoord.MISSING : fineCoord(actor, world, safeInt(world + wPlane, 0));
    }

    /** {@code coordFine()}: the local player's fine position, its height taken on the player's own plane field. */
    FineCoord localFineCoord() {
        long player = localPlayer();
        long world = actorWorld(player);
        if (player == 0 || world == 0) {
            return FineCoord.MISSING;
        }
        int plane = pLevel >= 0 ? safeInt(player + pLevel, 0) : safeInt(world + wPlane, 0);
        return fineCoord(player, world, plane);
    }

    private FineCoord fineCoord(long actor, long world, int plane) {
        if (actor == 0 || aFineX < 0 || aFineZ < 0) {
            return FineCoord.MISSING;
        }
        try {
            int x = memory.readInt(actor + aFineX);
            int z = memory.readInt(actor + aFineZ);
            return new FineCoord(x, height(world, x, z, plane), z);
        } catch (MemoryException e) {
            return FineCoord.MISSING;
        }
    }

    /**
     * The ground height at a fine position, as the client works it out: bilinear between the four corner heights of
     * the tile (one level up where the tile is under a bridge), minus the tile's ground decoration's height offset.
     * 0 off the scene. The client interpolates a few shaped tiles (with a surface or overlay model) over that shape's
     * triangles instead; this uses the corners for those too, so it can differ slightly inside them.
     */
    int height(long world, int fx, int fz, int plane) {
        return height(world, fx, fz, plane, true);
    }

    /**
     * The ground's own height at a fine position: bilinear between the tile's corner heights (one level up under a
     * bridge), without the ground decoration's offset that {@link #height(long, int, int, int)} subtracts for actors.
     * 0 off the scene.
     *
     * @param world the world whose scene to read
     * @param fx    the fine x
     * @param fz    the fine z
     * @param plane the level
     * @return the ground height (more negative is higher)
     */
    int groundHeight(long world, int fx, int fz, int plane) {
        return height(world, fx, fz, plane, false);
    }

    private int height(long world, int fx, int fz, int plane, boolean withDecor) {
        int tx = fx >> 7;
        int tz = fz >> 7;
        if (tx < 0 || tz < 0 || wScene < 0 || scSizeX < 0 || scSizeZ < 0 || scHeights < 0) {
            return 0;
        }
        long scene = safePointer(world + wScene);
        if (scene == 0) {
            return 0;
        }
        int sx = safeInt(scene + scSizeX, 0);
        int sz = safeInt(scene + scSizeZ, 0);
        if (tx > sx - 1 || tz > sz - 1) {
            return 0;
        }
        int h = bilinearHeight(scene, sx, sz, tx, tz, fx, fz, plane);
        if (!withDecor) {
            return h;
        }
        long tile = tile(scene, sx, sz, tx, tz, plane);
        if (tile != 0 && tileGroundDecor >= 0 && locTag >= 0 && locTagShift >= 0) {
            long decor = safePointer(tile + tileGroundDecor);
            if (decor != 0) {
                int locId = (int) (safeLong(decor + locTag, 0) >>> locTagShift & 0xffff);
                LocType type = configs.loc(locId);
                if (type != null) {
                    h -= type.getDecorHeightOffset();
                }
            }
        }
        return h;
    }

    private long tile(long scene, int sx, int sz, int tx, int tz, int plane) {
        if (scLevels < 0 || scTiles < 0 || scTileStride < 0 || scTilePtr < 0) {
            return 0;
        }
        if (plane < 0 || plane >= safeInt(scene + scLevels, 0)) {
            return 0;
        }
        long tiles = safePointer(scene + scTiles);
        long entry = ((long) (plane * sx + tx) * sz + tz) * scTileStride;
        return tiles == 0 ? 0 : safePointer(tiles + entry + scTilePtr);
    }

    private int bilinearHeight(long scene, int sx, int sz, int tx, int tz, int fx, int fz, int plane) {
        int level = plane;
        if (plane < 3 && scSettings >= 0) {
            long settings = safePointer(scene + scSettings);
            if (settings != 0 && (safeU8(settings + (long) (sx + tx) * sz + tz, 0) & 2) != 0) {
                level = plane + 1; // under a bridge: use the level above
            }
        }
        long heights = safePointer(scene + scHeights);
        if (heights == 0 || level < 0) {
            return 0;
        }
        int xf = fx & 127;
        int zf = fz & 127;
        int sw = cornerHeight(heights, sx, sz, level, tx, tz);
        int se = cornerHeight(heights, sx, sz, level, tx + 1, tz);
        int nw = cornerHeight(heights, sx, sz, level, tx, tz + 1);
        int ne = cornerHeight(heights, sx, sz, level, tx + 1, tz + 1);
        int south = sw * (128 - xf) + se * xf >> 7;
        int north = nw * (128 - xf) + ne * xf >> 7;
        return south * (128 - zf) + north * zf >> 7;
    }

    /** Corner heights are a (levels × (sizeX+1) × (sizeZ+1)) int array. */
    private int cornerHeight(long heights, int sx, int sz, int level, int x, int z) {
        long index = ((long) level * (sx + 1) + x) * (sz + 1) + z;
        return safeInt(heights + index * 4, 0);
    }

    /** {@code playerRouteLength}: how many steps the player's path has queued (0 standing still, or if not found). */
    int routeLength(long player) {
        return player == 0 || aPathLength < 0 ? 0 : safeInt(player + aPathLength, 0);
    }

    /**
     * {@code getNpcIdByNameAll}: the index of every NPC in each world's active NPC list whose name (as
     * {@link #npcName} shows it) is exactly {@code name}, case-sensitive. Empty if none.
     */
    int[] npcIdsByName(String name) {
        int[] out = new int[16];
        int n = 0;
        for (long world : worlds()) {
            int count = safeInt(world + wNpcCount, 0);
            long base = safePointer(world + wNpcIndices);
            if (base == 0 || count <= 0 || count > MAX_INDICES) {
                continue;
            }
            int[] indices = readInts(base, count);
            if (indices == null) {
                continue;
            }
            for (int index : indices) {
                long npc = structs.hashGet(world + wNpcs, index);
                if (npc != 0 && npcName(npc).equals(name)) {
                    if (n == out.length) {
                        out = java.util.Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = index;
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * {@code getNpcIdByName}: the index of the first NPC (walking each world's NPC table) whose name is exactly
     * {@code name}, or -1. The native handler then looks the NPC up again under its uid instead of its index, which
     * finds nothing for almost every NPC, so in Lua it returns -1; this returns the NPC it found.
     */
    int npcIdByName(String name) {
        for (long world : worlds()) {
            int[] found = {-1};
            forEachNode(world + wNpcs, (key, npc) -> {
                if (npc != 0 && npcName(npc).equals(name)) {
                    found[0] = key;
                    return true;
                }
                return false;
            });
            if (found[0] != -1) {
                return found[0];
            }
        }
        return -1;
    }

    /**
     * {@code getPlayerIdByName}: the index of the first player (walking each world's player table) whose normalised
     * name equals {@code name} lower-cased (ASCII letters only, as the client's {@code tolower} does), or -1.
     */
    int playerIdByName(String name) {
        if (pnCanonical < 0) {
            return -1;
        }
        String wanted = asciiLower(name);
        for (long world : worlds()) {
            int[] found = {-1};
            forEachNode(world + wPlayers, (key, player) -> {
                long pair = player == 0 ? 0 : safePointer(player + pName);
                if (pair != 0 && structs.string(pair + pnCanonical).equals(wanted)) {
                    found[0] = pIndex >= 0 ? safeInt(player + pIndex, key) : key;
                    return true;
                }
                return false;
            });
            if (found[0] != -1) {
                return found[0];
            }
        }
        return -1;
    }

    private static String asciiLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return out.toString();
    }

    interface NodeVisitor {

        /**
         * Visits one hash map entry.
         *
         * @param key   the entry's key
         * @param value the entry's value pointer
         * @return true to stop walking
         */
        boolean visit(int key, long value);
    }

    /**
     * Walks an eastl::hash_map (bucket order, then each chain) until the visitor says stop.
     *
     * @param map     address of the hash_map object
     * @param visitor called for each entry
     */
    void forEachNode(long map, NodeVisitor visitor) {
        long buckets = safePointer(map + mapBuckets);
        long count = safeLong(map + mapBucketCount, 0);
        if (buckets == 0 || count <= 0 || count > MAX_BUCKETS) {
            return;
        }
        ByteBuffer heads = readBlock(buckets, (int) count * 8);
        if (heads == null) {
            return;
        }
        int guard = 0;
        for (int b = 0; b < count; b++) {
            for (long node = heads.getLong(b * 8); node != 0 && guard++ < MAX_CHAIN;
                 node = safePointer(node + nodeNext)) {
                if (visitor.visit(safeInt(node + nodeKey, -1), safePointer(node + nodeValue))) {
                    return;
                }
            }
        }
    }

    /** {@code isInteracting}: the target's index if the target type is set, else -1. */
    int interacting(long actor) {
        if (actor == 0 || aInteractingType < 0 || aInteracting < 0) {
            return -1;
        }
        return safeU8(actor + aInteractingType, 0) != 0 ? safeInt(actor + aInteracting, -1) : -1;
    }

    /** The target type byte: 0 none, 1 NPC, 2 player, 3 world entity. */
    int interactingType(long actor) {
        if (actor == 0 || aInteractingType < 0) {
            return Entity.TARGET_NONE;
        }
        return safeU8(actor + aInteractingType, Entity.TARGET_NONE);
    }

    boolean rendered3D(long actor) {
        return actor == 0 || aHidden < 0 || safeU8(actor + aHidden, 0) == 0;
    }

    boolean rendered2D(long actor) {
        return actor == 0 || aRender2D < 0 || safeU8(actor + aRender2D, 1) != 0;
    }

    /** {@code isHealthbarRendered}: some head bar in the actor's list is a health bar (head bar type 0). */
    boolean healthbarRendered(long actor) {
        if (!hasHeadbars()) {
            return false;
        }
        for (long headbar : headbars(actor)) {
            long type = safePointer(headbar + hbType);
            if (type != 0 && safeInt(type + hbtId, -1) == 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code isHeadbarEmpty}: some head bar's current update shows both health values at zero. */
    boolean headbarEmpty(long actor) {
        if (!hasHeadbars() || hueCycle < 0 || hueHealth < 0 || hueHealth2 < 0 || hueCycleOffset < 0
                || hbtDuration < 0 || cycleRva < 0) {
            return false;
        }
        int cycle = safeInt(memory.moduleBase() + cycleRva, 0);
        for (long headbar : headbars(actor)) {
            long update = currentUpdate(headbar, cycle);
            if (update != 0 && safeInt(update + hueHealth, -1) == 0 && safeInt(update + hueHealth2, -1) == 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code isDead}: the native handler is exactly {@code isHealthbarRendered && isHeadbarEmpty}. */
    boolean dead(long actor) {
        return healthbarRendered(actor) && headbarEmpty(actor);
    }

    private boolean hasHeadbars() {
        return aHeadbars >= 0 && aHeadbarCount >= 0 && hbType >= 0 && hbtId >= 0 && hbUpdates >= 0
                && hbUpdateCount >= 0;
    }

    /** The head bars in the actor's list (the list holds shared_ptrs; each node's value is the head bar). */
    private long[] headbars(long actor) {
        if (actor == 0 || safeLong(actor + aHeadbarCount, 0) <= 0) {
            return new long[0];
        }
        return listValues(actor + aHeadbars);
    }

    /** The values of an eastl::list given its sentinel, at most {@link #MAX_LIST} of them. */
    private long[] listValues(long sentinel) {
        long[] values = new long[MAX_LIST];
        int n = 0;
        long node = safePointer(sentinel + listNext);
        while (node != 0 && node != sentinel && n < MAX_LIST) {
            long value = safePointer(node + listValue);
            if (value != 0) {
                values[n++] = value;
            }
            node = safePointer(node + listNext);
        }
        return java.util.Arrays.copyOf(values, n);
    }

    /**
     * The head bar's update showing at {@code cycle}, or 0, like the client's lookup: the last update whose cycle has
     * started, if it hasn't expired (type duration + its offset + its cycle still ahead of now). The client also prunes
     * stale updates as it goes; that doesn't change the answer.
     */
    private long currentUpdate(long headbar, int cycle) {
        if (safeLong(headbar + hbUpdateCount, 0) <= 0) {
            return 0;
        }
        long sentinel = headbar + hbUpdates;
        long first = safePointer(sentinel + listNext);
        if (first == 0 || first == sentinel) {
            return 0;
        }
        long firstUpdate = safePointer(first + listValue);
        if (firstUpdate == 0 || safeInt(firstUpdate + hueCycle, Integer.MAX_VALUE) > cycle) {
            return 0;
        }
        long node = first;
        for (int guard = 0; node != 0 && node != sentinel && guard < MAX_LIST; guard++) {
            long update = safePointer(node + listValue);
            if (update == 0 || safeInt(update + hueCycle, Integer.MAX_VALUE) > cycle) {
                break;
            }
            node = safePointer(node + listNext);
        }
        long selected = safePointer(node + listPrev);
        long update = selected == 0 ? 0 : safePointer(selected + listValue);
        long type = safePointer(headbar + hbType);
        if (update == 0 || type == 0) {
            return 0;
        }
        long expires = (long) safeInt(type + hbtDuration, 0) + safeInt(update + hueCycleOffset, 0)
                + safeInt(update + hueCycle, 0);
        return expires > cycle ? update : 0;
    }

    /** The NPC's base type struct (what {@code Npc.type} points at), or 0. */
    long npcTypeStruct(long npc) {
        return npc == 0 ? 0 : safePointer(npc + npcType);
    }

    /** {@code NpcType.id} of the NPC's base type, or -1. */
    int npcTypeId(long npc) {
        long type = npcTypeStruct(npc);
        return type == 0 ? -1 : safeInt(type + ntId, -1);
    }

    /**
     * The NPC's name as the client shows it: its own name if it has one, otherwise its type's name, following the
     * type's multi-NPC transform (a varbit or varp picks which child type it currently is) when it has one.
     */
    String npcName(long npc) {
        if (npc == 0) {
            return "";
        }
        String own = structs.string(npc + aName);
        if (!own.isEmpty()) {
            return own;
        }
        long type = npcTypeStruct(npc);
        if (type == 0) {
            return "";
        }
        int child = multiNpcChild(type);
        if (child != -1) {
            NpcType transformed = configs.npc(child);
            if (transformed != null) {
                return transformed.getName();
            }
        }
        return structs.string(type + ntName);
    }

    /**
     * The child type id a multi-NPC type currently shows, or -1 if the type has no transform (or it can't be read).
     * Same choice as the client: index = the varbit's value, else the varp's, else -1; an index outside the list
     * (or -1) takes the last entry.
     */
    private int multiNpcChild(long type) {
        if (ntMultiCount < 0 || ntMulti < 0 || ntMultiVarbit < 0 || ntMultiVarp < 0) {
            return -1;
        }
        long count = safeLong(type + ntMultiCount, 0);
        if (count <= 0 || count > 1024) {
            return -1;
        }
        long children = safePointer(type + ntMulti);
        if (children == 0) {
            return -1;
        }
        int varbit = safeInt(type + ntMultiVarbit, -1);
        int varp = safeInt(type + ntMultiVarp, -1);
        int index = varbit != -1 ? varbitValue(varbit) : varp != -1 ? varpBits(varp) : -1;
        int slot = index < 0 || index >= count - 1 ? (int) count - 1 : index;
        return safeInt(children + slot * 4L, -1);
    }

    /** The NPC's category ({@code NpcType.type} of its base type), or -1. */
    int npcCategory(long npc) {
        long type = npcTypeStruct(npc);
        return type == 0 || ntCategory < 0 ? -1 : safeInt(type + ntCategory, -1);
    }

    boolean npcIsFollower(long npc) {
        long type = npcTypeStruct(npc);
        return type != 0 && ntIsFollower >= 0 && safeU8(type + ntIsFollower, 0) != 0;
    }

    /**
     * {@code isLocalFollower}: one of the varps the client tracks as follower slots (the server marks them; the client
     * collects their ids) holds this NPC's index in its low 16 bits.
     */
    boolean npcIsLocalFollower(int index) {
        long client = clientBase();
        if (client == 0 || cFollowerVarps < 0 || cFollowerVarpsEnd < 0 || varpsBitsRva < 0) {
            return false;
        }
        long begin = safePointer(client + cFollowerVarps);
        long end = safePointer(client + cFollowerVarpsEnd);
        long count = (end - begin) >> 2;
        if (begin == 0 || count <= 0 || count > MAX_FOLLOWER_VARPS) {
            return false;
        }
        int[] varps = readInts(begin, (int) count);
        if (varps == null) {
            return false;
        }
        for (int varp : varps) {
            int value = varpBits(varp);
            if (value != Integer.MIN_VALUE && (value & 0xffff) == index) {
                return true;
            }
        }
        return false;
    }

    /** {@code npcUid}: type id × 65536 + the NPC's index, or -1. */
    int npcUid(long npc) {
        long type = npcTypeStruct(npc);
        if (type == 0 || npcIndex < 0) {
            return -1;
        }
        return safeInt(type + ntId, 0) * 0x10000 + safeInt(npc + npcIndex, 0);
    }

    /** {@code npcCreationCycle}: the client cycle the NPC was created on, or -1. */
    int npcCreationCycle(long npc) {
        return npc == 0 || npcSpawnCycle < 0 ? -1 : safeInt(npc + npcSpawnCycle, -1);
    }

    /** The player's display name (the client cuts it at the first NUL), or "". */
    String playerName(long player) {
        if (player == 0) {
            return "";
        }
        String name = structs.string(safePointer(player + pName));
        int nul = name.indexOf('\0');
        return nul < 0 ? name : name.substring(0, nul);
    }

    /** The normalised name friend and ignore lists are keyed by, or "". */
    private String playerCanonicalName(long player) {
        if (player == 0 || pnCanonical < 0) {
            return "";
        }
        long pair = safePointer(player + pName);
        return pair == 0 ? "" : structs.string(pair + pnCanonical);
    }

    /**
     * {@code isOnlineFriend}. The client caches the answer on the player (0 not worked out yet, 1 yes, 2 no) and fills
     * it in when it draws players; when it hasn't yet, this looks the name up in the friend list like the client
     * would (a friend counts as online when their world is above 0).
     */
    boolean playerIsOnlineFriend(long player) {
        int cached = cachedState(player, pFriendState);
        if (cached != 0) {
            return cached == 1;
        }
        long manager = friendManager();
        if (manager == 0 || fmFriends < 0 || friendWorld < 0) {
            return false;
        }
        long friend = findName(manager + fmFriends, playerCanonicalName(player));
        return friend != 0 && safeInt(friend + friendWorld, 0) > 0;
    }

    /** {@code isFriendChat}: cached like {@link #playerIsOnlineFriend}; otherwise the name is in the friends chat. */
    boolean playerIsFriendChat(long player) {
        int cached = cachedState(player, pFriendChatState);
        if (cached != 0) {
            return cached == 1;
        }
        long client = clientBase();
        long chat = client == 0 || cFriendsChat < 0 ? 0 : safePointer(client + cFriendsChat);
        return chat != 0 && findNode(chat, playerCanonicalName(player)) != 0;
    }

    /**
     * {@code isGroup}: in a clan with the local player. Reads the client's cached answer; when the client hasn't
     * worked it out yet (it does for players it draws), this says false rather than walking the clan channels.
     */
    boolean playerIsGroup(long player) {
        return cachedState(player, pGroupState) == 1;
    }

    /** {@code isIgnored}: the player's name (current or previous) is on the ignore list. */
    boolean playerIsIgnored(long player) {
        long manager = friendManager();
        return manager != 0 && fmIgnores >= 0 && findNode(manager + fmIgnores, playerCanonicalName(player)) != 0;
    }

    private int cachedState(long player, int offset) {
        return player == 0 || offset < 0 ? 2 : safeInt(player + offset, 2);
    }

    private long friendManager() {
        long client = clientBase();
        return client == 0 || cFriendManager < 0 ? 0 : safePointer(client + cFriendManager);
    }

    /** The value (e.g. a Friend) stored for {@code canonical} in a name list, or 0. */
    private long findName(long list, String canonical) {
        long node = findNode(list, canonical);
        return node == 0 || nameNodeValue < 0 ? 0 : safePointer(node + nameNodeValue);
    }

    /**
     * The node for {@code canonical} in a name list (two string-keyed hash maps: current names and previous names), or
     * 0. Scans every node instead of hashing; the lists are small.
     */
    private long findNode(long list, String canonical) {
        if (canonical.isEmpty() || nlByName < 0 || nlByPrevName < 0 || nameNodeNext < 0 || pnCanonical < 0) {
            return 0;
        }
        for (int table : new int[]{nlByName, nlByPrevName}) {
            long map = list + table;
            long buckets = safePointer(map + mapBuckets);
            long count = safeLong(map + mapBucketCount, 0) & 0xffffffffL;
            if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
                continue;
            }
            ByteBuffer heads = readBlock(buckets, (int) count * 8);
            if (heads == null) {
                continue;
            }
            int guard = 0;
            for (int b = 0; b < count; b++) {
                for (long node = heads.getLong(b * 8); node != 0 && guard++ < MAX_CHAIN;
                     node = safePointer(node + nameNodeNext)) {
                    if (canonical.equals(structs.string(node + pnCanonical))) {
                        return node;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Reads vars the way the client resolves conditional item ops: varps from the copy varbits read, varbits through
     * their definitions. -1 for anything unreadable.
     */
    final ObjType.VarReader vars = new ObjType.VarReader() {
        @Override
        public int varp(int id) {
            int value = varpBits(id);
            return value == Integer.MIN_VALUE ? -1 : value;
        }

        @Override
        public int varbit(int id) {
            return varbitValue(id);
        }
    };

    /**
     * Whether the client is on a members world: bit 0 of the world-type flags. The item loader tests a byte the client
     * sets from the same world record bit, so this picks the same item rewrites.
     */
    boolean membersWorld() {
        long client = clientBase();
        return client != 0 && cWorldFlags >= 0 && (safeInt(client + cWorldFlags, 0) & 1) != 0;
    }

    /** {@code varpsBits[varp]}, or {@link Integer#MIN_VALUE} if unreadable. */
    private int varpBits(int varp) {
        if (varpsBitsRva < 0 || varp < 0) {
            return Integer.MIN_VALUE;
        }
        long bits = safePointer(memory.moduleBase() + varpsBitsRva);
        return bits == 0 ? Integer.MIN_VALUE : safeInt(bits + varp * 4L, Integer.MIN_VALUE);
    }

    /** A varbit's value as the client's varbit reader computes it, or -1. */
    private int varbitValue(int varbit) {
        VarBitType type = configs.varbit(varbit);
        if (type == null) {
            return -1;
        }
        int varp = varpBits(type.baseVar());
        return varp == Integer.MIN_VALUE ? -1 : type.extract(varp);
    }
}
