package com.kraken.api.internal.reader;

import com.kraken.api.ActorSpotAnim;
import com.kraken.api.Client;
import com.kraken.api.Hitsplat;
import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.FineCoord;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.OverheadText;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads players and NPCs: finding one by index, and everything {@link Actor}, {@link NPC} and {@link Player} read from
 * it, by the actor's address. {@link Client} and every actor handle share one instance. Each method replays what a
 * native Lua handler ({@code ClientOp}'s actor functions, the {@code osrs.Entity}/{@code Npc}/{@code Player} methods)
 * reads; where it differs, its Javadoc says so.
 *
 * <p>Two ways of finding an actor, as in the client: by index in the top-level world ({@link #npc}, {@link #player},
 * what the handles use), or in every loaded world, the main one and sub-worlds such as boats ({@link #npcInAnyWorld},
 * {@link #playerInAnyWorld}, what Lua's {@code ClientOp} functions use).
 *
 * <p>Keys added to the mapping after the first cut are optional here: without them, just the methods that need them
 * return their "missing" values.
 */
public final class ActorReader extends GameReader {

    /**
     * Highest NPC index the client's top-level lookup accepts (Lua's {@code getNpcObj}).
     */
    static final int MAX_NPC_INDEX = 0xffff;

    /**
     * Highest player index the client's top-level lookup accepts (Lua's {@code getPlayerObj}).
     */
    static final int MAX_PLAYER_INDEX = 0x7ff;

    private static final int MAX_LIST = 64;          // headbars per actor, updates per headbar
    private static final int MAX_HITSPLATS = 16;
    private static final int MAX_SPOT_ANIMS = 256;     // one per slot, and a slot is a byte
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
    private final int aHitmarks;        // eastl::vector<Hitmark>: begin, then end
    private final int aHitmarksEnd;
    private final int aAnim;

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

    // Hitmarks
    private final int hmType;
    private final int hmValue;
    private final int hmEndCycle;
    private final int hmSize;

    // Overhead text and spot animations
    private final int aOverheadText;    // eastl::string
    private final int aOverheadTimer;
    private final int aSpotAnims;       // eastl::vector_map<int, shared_ptr<ActorSpotAnim>>: begin, then end
    private final int aSpotAnimsEnd;
    private final int sasSize;
    private final int sasSlot;
    private final int sasSpotAnim;
    private final int saId;
    private final int saHeight;
    private final int saStartCycle;
    private final int saFrame;

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

    public ActorReader(Memory memory, Mapping mapping, ConfigTypes configs) {
        super(memory, mapping);
        this.configs = configs;

        this.cTopWorld = mapping.offset("Client.topLevelWorld");
        this.cWorldsMap = optionalOffset("Client.worlds.map");
        this.cLocalIndex = mapping.offset("Client.localPlayerIndex");
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
        this.aHitmarks = optionalOffset("Actor.hitmarks");
        this.aHitmarksEnd = optionalOffset("Actor.hitmarksEnd");
        this.aAnim = optionalOffset("Actor.anim");

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
        this.hmType = optionalOffset("Hitmark.type");
        this.hmValue = optionalOffset("Hitmark.value");
        this.hmEndCycle = optionalOffset("Hitmark.endCycle");
        this.hmSize = optionalOffset("Hitmark.size");
        this.aOverheadText = optionalOffset("Actor.overheadText");
        this.aOverheadTimer = optionalOffset("Actor.overheadTimer");
        this.aSpotAnims = optionalOffset("Actor.spotAnims");
        this.aSpotAnimsEnd = optionalOffset("Actor.spotAnimsEnd");
        this.sasSize = optionalOffset("SpotAnimSlot.size");
        this.sasSlot = optionalOffset("SpotAnimSlot.slot");
        this.sasSpotAnim = optionalOffset("SpotAnimSlot.spotAnim");
        this.saId = optionalOffset("ActorSpotAnim.id");
        this.saHeight = optionalOffset("ActorSpotAnim.height");
        this.saStartCycle = optionalOffset("ActorSpotAnim.startCycle");
        this.saFrame = optionalOffset("ActorSpotAnim.animFrame");

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

    public long topWorld() {
        long client = clientBase();
        return client == 0 ? 0 : safePointer(client + cTopWorld);
    }

    /**
     * Every loaded world (the main one and sub-worlds), in the order the client's lookups walk them. Just the top-level
     * world if the mapping doesn't have the worlds table or it can't be read.
     */
    public long[] worlds() {
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
    public long npc(int index) {
        if (index < 0 || index > MAX_NPC_INDEX) {
            return 0;
        }
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wNpcs, index);
    }

    /** The player at {@code index} in the top-level world, or 0. What the Lua {@code Player} object resolves to. */
    public long player(int index) {
        if (index < 0 || index > MAX_PLAYER_INDEX) {
            return 0;
        }
        long world = topWorld();
        return world == 0 ? 0 : structs.hashGet(world + wPlayers, index);
    }

    /** The world an actor is in: the top-level world for world id 0, otherwise that sub-world (0 if not loaded). */
    public long actorWorld(long actor) {
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

    /**
     * A world's current plane ({@code World.plane}), the level its actors' tiles are reported on.
     *
     * @param world the world's address
     * @return the plane, or -1 if the world isn't loaded or can't be read
     */
    public int plane(long world) {
        return world == 0 ? -1 : safeInt(world + wPlane, -1);
    }

    public int localPlayerIndex() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cLocalIndex, -1);
    }

    /** The actor's tile, on its world's plane ({@code npcCoord}/{@code playerCoord}), or null. */
    public MapCoord coord(long actor) {
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

    /**
     * {@code npcCoordFine}/{@code playerCoordFine}: the actor's fine position with the ground height at it on its
     * world's plane; {@link FineCoord#MISSING} if the actor or its world isn't loaded.
     */
    public FineCoord fineCoord(long actor) {
        long world = actorWorld(actor);
        return world == 0 ? FineCoord.MISSING : fineCoord(actor, world, safeInt(world + wPlane, 0));
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
    public int height(long world, int fx, int fz, int plane) {
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
    public int groundHeight(long world, int fx, int fz, int plane) {
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
                ObjectComposition type = configs.loc(locId);
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
    public int routeLength(long player) {
        return player == 0 || aPathLength < 0 ? 0 : safeInt(player + aPathLength, 0);
    }

    /**
     * {@code getNpcIdByNameAll}: the index of every NPC in each world's active NPC list whose name (as
     * {@link #npcName} shows it) is exactly {@code name}, case-sensitive. Empty if none.
     */
    public int[] npcIdsByName(String name) {
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
    public int npcIdByName(String name) {
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
    public int playerIdByName(String name) {
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

    public interface NodeVisitor {

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
    public void forEachNode(long map, NodeVisitor visitor) {
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
    public int interacting(long actor) {
        if (actor == 0 || aInteractingType < 0 || aInteracting < 0) {
            return -1;
        }
        return safeU8(actor + aInteractingType, 0) != 0 ? safeInt(actor + aInteracting, -1) : -1;
    }

    /** The target type byte: 0 none, 1 NPC, 2 player, 3 world entity. */
    public int interactingType(long actor) {
        if (actor == 0 || aInteractingType < 0) {
            return Actor.INTERACTING_NONE;
        }
        return safeU8(actor + aInteractingType, Actor.INTERACTING_NONE);
    }

    public boolean rendered3D(long actor) {
        return actor == 0 || aHidden < 0 || safeU8(actor + aHidden, 0) == 0;
    }

    public boolean rendered2D(long actor) {
        return actor == 0 || aRender2D < 0 || safeU8(actor + aRender2D, 1) != 0;
    }

    /**
     * The actor's animation: the sequence the client last set ({@code Actor.anim}, which {@code setSequence} and the
     * player's sequence setter write), set back to -1 when the animation finishes or is cancelled. Not a Lua read: no
     * Lua function reads it.
     *
     * @param actor the actor's address
     * @return the sequence id, -1 for none; null if it can't be read or this build's mapping lacks it
     */
    public Integer readAnimation(long actor) {
        if (actor == 0 || aAnim < 0) {
            return null;
        }
        try {
            return memory.readInt(actor + aAnim);
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * {@code isHealthbarRendered}: some head bar in the actor's list is a health bar (head bar type 0).
     *
     * @param actor the actor's address
     * @return true if a health bar shows; false if not, or if the head bars can't be read
     */
    public boolean healthbarRendered(long actor) {
        try {
            long[] headbars = headbars(actor);
            return headbars != null && hasHealthbar(headbars);
        } catch (MemoryException e) {
            return false;
        }
    }

    /**
     * {@code isHeadbarEmpty}: some head bar's current update shows both health values at zero.
     *
     * @param actor the actor's address
     * @return true if a head bar shows empty; false if not, or if the head bars can't be read
     */
    public boolean headbarEmpty(long actor) {
        try {
            long[] headbars = headbars(actor);
            return headbars != null && Boolean.TRUE.equals(showsEmpty(headbars));
        } catch (MemoryException e) {
            return false;
        }
    }

    /**
     * {@code isDead}: the native handler is exactly {@code isHealthbarRendered && isHeadbarEmpty}.
     *
     * @param actor the actor's address
     * @return true if dead; false if not, or if the head bars can't be read
     */
    public boolean dead(long actor) {
        return Boolean.TRUE.equals(readDead(actor));
    }

    /**
     * {@link #dead}, read strictly for the death events: a head bar or update list that can't be read or breaks off
     * gives null rather than "not dead", so a failed read never looks like the actor coming back to life.
     *
     * @param actor the actor's address
     * @return true if dead, false if not; null if the head bars can't be read or this build's mapping lacks their keys
     */
    public Boolean readDead(long actor) {
        try {
            long[] headbars = headbars(actor);
            if (headbars == null) {
                return null;
            }
            return hasHealthbar(headbars) ? showsEmpty(headbars) : Boolean.FALSE;
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * The hitsplats an actor holds (the client's hitmarks), in the order of its slots. The client keeps a few per
     * actor and writes each new one over a slot whose hitsplat has ended, onto the end while there is room, or over
     * the one its type's rule picks, so the list holds ended hitsplats too. Not a Lua read.
     *
     * @param actor the actor's address
     * @return the hitsplats, empty if it holds none; null if they can't be read or this build's mapping lacks them
     */
    public List<Hitsplat> hitsplats(long actor) {
        if (actor == 0 || aHitmarks < 0 || aHitmarksEnd < 0 || hmType < 0 || hmValue < 0 || hmEndCycle < 0
                || hmSize <= 0) {
            return null;
        }
        long begin;
        long end;
        if (aHitmarksEnd == aHitmarks + 8) {
            ByteBuffer pair = readBlock(actor + aHitmarks, 16);
            if (pair == null) {
                return null;
            }
            begin = pair.getLong(0);
            end = pair.getLong(8);
        } else {
            begin = safeLong(actor + aHitmarks, -1);
            end = safeLong(actor + aHitmarksEnd, -1);
        }
        if (begin == -1 || end == -1) {
            return null;
        }
        if (begin == end) {
            return List.of();
        }
        long bytes = end - begin;
        if (begin == 0 || bytes <= 0 || bytes % hmSize != 0 || bytes / hmSize > MAX_HITSPLATS) {
            return null;
        }
        ByteBuffer entries = readBlock(begin, (int) bytes);
        if (entries == null) {
            return null;
        }
        int count = (int) (bytes / hmSize);
        List<Hitsplat> hitsplats = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = i * hmSize;
            hitsplats.add(new Hitsplat(entries.getInt(at + hmType), entries.getInt(at + hmValue),
                    entries.getInt(at + hmEndCycle)));
        }
        return hitsplats;
    }

    /**
     * The text an actor shows over its head, and how long it has left. The NPC and player updates (an NPC's shout, a
     * player's public chat or forced line) and Lua's {@code npcSay} and {@code playerSay} write both; the client counts
     * the timer down by one per actor step and empties the text when it reaches 0. Not a Lua read: no Lua function
     * reads them.
     *
     * @param actor the actor's address
     * @return the text ("" when none shows) and its timer; null if they can't be read or this build's mapping lacks
     *         them
     */
    public OverheadText readOverheadText(long actor) {
        if (actor == 0 || aOverheadText < 0 || aOverheadTimer < 0) {
            return null;
        }
        int from = Math.min(aOverheadText, aOverheadTimer);
        int span = Math.max(aOverheadText + structs.stringSize(), aOverheadTimer + 4) - from;
        try {
            if (span > 0x100) {
                byte[] text = memory.read(actor + aOverheadText, structs.stringSize());
                return new OverheadText(structs.readString(text, actor + aOverheadText, 0),
                        memory.readInt(actor + aOverheadTimer));
            }
            ByteBuffer block = readBlock(actor + from, span);
            if (block == null) {
                return null;
            }
            return new OverheadText(structs.readString(block.array(), actor + from, aOverheadText - from),
                    block.getInt(aOverheadTimer - from));
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * The time an actor's overhead text has left, as {@link #readOverheadText} reads it.
     *
     * @param actor the actor's address
     * @return the client cycles left; 0 if it can't be read or this build's mapping lacks it
     */
    public int overheadCycles(long actor) {
        return actor == 0 || aOverheadTimer < 0 ? 0 : safeInt(actor + aOverheadTimer, 0);
    }

    /**
     * The spot animations an actor shows, in slot order (the client keeps them sorted by slot), the ones that haven't
     * started yet included. The NPC and player updates and the server's spot animation packets set and clear them; the
     * client drops one in its per-cycle actor step once its animation has played. Read strictly: a slot list or a spot
     * animation that can't be read gives null rather than fewer spot animations. Not a Lua read.
     *
     * @param actor the actor's address
     * @return the spot animations, empty if it shows none; null if they can't be read or this build's mapping lacks
     *         them
     */
    public List<ActorSpotAnim> readSpotAnims(long actor) {
        if (actor == 0 || aSpotAnims < 0 || aSpotAnimsEnd < 0 || sasSize <= 0 || sasSlot < 0 || sasSpotAnim < 0
                || saId < 0 || saHeight < 0 || saStartCycle < 0 || saFrame < 0) {
            return null;
        }
        long begin;
        long end;
        if (aSpotAnimsEnd == aSpotAnims + 8) {
            ByteBuffer pair = readBlock(actor + aSpotAnims, 16);
            if (pair == null) {
                return null;
            }
            begin = pair.getLong(0);
            end = pair.getLong(8);
        } else {
            begin = safeLong(actor + aSpotAnims, -1);
            end = safeLong(actor + aSpotAnimsEnd, -1);
        }
        if (begin == -1 || end == -1) {
            return null;
        }
        if (begin == end) {
            return List.of();
        }
        long bytes = end - begin;
        if (begin == 0 || bytes <= 0 || bytes % sasSize != 0 || bytes / sasSize > MAX_SPOT_ANIMS) {
            return null;
        }
        ByteBuffer slots = readBlock(begin, (int) bytes);
        if (slots == null) {
            return null;
        }
        int count = (int) (bytes / sasSize);
        List<ActorSpotAnim> spotAnims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int at = i * sasSize;
            ActorSpotAnim spotAnim = readSpotAnim(slots.getInt(at + sasSlot), slots.getLong(at + sasSpotAnim));
            if (spotAnim == null) {
                return null;
            }
            spotAnims.add(spotAnim);
        }
        return spotAnims;
    }

    /**
     * One spot animation an actor's slot points at: its fields in one read when they lie close together.
     *
     * @param slot     the slot
     * @param spotAnim the spot animation's address
     * @return the spot animation; null if it can't be read
     */
    private ActorSpotAnim readSpotAnim(int slot, long spotAnim) {
        if (spotAnim == 0) {
            return null;
        }
        int from = Math.min(Math.min(saId, saHeight), Math.min(saStartCycle, saFrame));
        int span = Math.max(Math.max(saId, saHeight), Math.max(saStartCycle, saFrame - 2)) + 4 - from;
        try {
            if (span > 0x100) {
                return new ActorSpotAnim(slot, memory.readInt(spotAnim + saId), memory.readInt(spotAnim + saHeight),
                        memory.readInt(spotAnim + saStartCycle), (short) memory.readShort(spotAnim + saFrame));
            }
        } catch (MemoryException e) {
            return null;
        }
        ByteBuffer fields = readBlock(spotAnim + from, span);
        if (fields == null) {
            return null;
        }
        return new ActorSpotAnim(slot, fields.getInt(saId - from), fields.getInt(saHeight - from),
                fields.getInt(saStartCycle - from), fields.getShort(saFrame - from));
    }

    /**
     * The spot animations an actor shows, as {@link #readSpotAnims} reads them.
     *
     * @param actor the actor's address
     * @return the spot animations; empty if it shows none, if they can't be read or this build's mapping lacks them
     */
    public List<ActorSpotAnim> spotAnims(long actor) {
        List<ActorSpotAnim> spotAnims = readSpotAnims(actor);
        return spotAnims == null ? List.of() : spotAnims;
    }

    private boolean hasHeadbars() {
        return aHeadbars >= 0 && aHeadbarCount >= 0 && hbType >= 0 && hbtId >= 0 && hbUpdates >= 0
                && hbUpdateCount >= 0;
    }

    /**
     * Whether some head bar is a health bar (head bar type 0).
     *
     * @param headbars the actor's head bars
     * @return true if one is a health bar
     * @throws MemoryException if a head bar or its type can't be read
     */
    private boolean hasHealthbar(long[] headbars) {
        for (long headbar : headbars) {
            long type = memory.readPointer(headbar + hbType);
            if (type != 0 && memory.readInt(type + hbtId) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether some head bar's current update shows both health values at zero.
     *
     * @param headbars the actor's head bars
     * @return true if one shows empty, false if none does; null if an update list breaks off or this build's mapping
     *         lacks the update keys
     * @throws MemoryException if the cycle, an update or a head bar type can't be read
     */
    private Boolean showsEmpty(long[] headbars) {
        if (hueCycle < 0 || hueHealth < 0 || hueHealth2 < 0 || hueCycleOffset < 0 || hbtDuration < 0 || cycleRva < 0) {
            return null;
        }
        int cycle = memory.readInt(memory.moduleBase() + cycleRva);
        for (long headbar : headbars) {
            long update = currentUpdate(headbar, cycle);
            if (update == -1) {
                return null;
            }
            if (update != 0 && memory.readInt(update + hueHealth) == 0 && memory.readInt(update + hueHealth2) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The head bars in the actor's list (the list holds shared_ptrs; each node's value is the head bar), at most
     * {@link #MAX_LIST} of them.
     *
     * @param actor the actor's address
     * @return the head bars; null if the actor is 0, the list breaks off (a null link) or this build's mapping lacks
     *         the head bar keys
     * @throws MemoryException if the count or a link can't be read
     */
    private long[] headbars(long actor) {
        if (actor == 0 || !hasHeadbars()) {
            return null;
        }
        if (memory.readLong(actor + aHeadbarCount) <= 0) {
            return new long[0];
        }
        long sentinel = actor + aHeadbars;
        long[] values = new long[MAX_LIST];
        int n = 0;
        long node = memory.readPointer(sentinel + listNext);
        for (int guard = 0; node != sentinel && guard < MAX_LIST; guard++) {
            if (node == 0) {
                return null;
            }
            long value = memory.readPointer(node + listValue);
            if (value != 0) {
                values[n++] = value;
            }
            node = memory.readPointer(node + listNext);
        }
        return java.util.Arrays.copyOf(values, n);
    }

    /**
     * The head bar's update showing at {@code cycle}, like the client's lookup: the last update whose cycle has
     * started, if it hasn't expired (type duration + its offset + its cycle still ahead of now). The client also prunes
     * stale updates as it goes; that doesn't change the answer.
     *
     * @param headbar the head bar's address
     * @param cycle   the client cycle now
     * @return the update's address; 0 if none shows; -1 if the update list breaks off (a null link)
     * @throws MemoryException if a link or a field can't be read
     */
    private long currentUpdate(long headbar, int cycle) {
        if (memory.readLong(headbar + hbUpdateCount) <= 0) {
            return 0;
        }
        long sentinel = headbar + hbUpdates;
        long first = memory.readPointer(sentinel + listNext);
        if (first == 0) {
            return -1;
        }
        if (first == sentinel) {
            return 0;
        }
        long firstUpdate = memory.readPointer(first + listValue);
        if (firstUpdate == 0 || memory.readInt(firstUpdate + hueCycle) > cycle) {
            return 0;
        }
        long node = first;
        for (int guard = 0; node != sentinel && guard < MAX_LIST; guard++) {
            if (node == 0) {
                return -1;
            }
            long update = memory.readPointer(node + listValue);
            if (update == 0 || memory.readInt(update + hueCycle) > cycle) {
                break;
            }
            node = memory.readPointer(node + listNext);
        }
        if (node == 0) {
            return -1;
        }
        long selected = memory.readPointer(node + listPrev);
        if (selected == 0) {
            return -1;
        }
        long update = memory.readPointer(selected + listValue);
        long type = memory.readPointer(headbar + hbType);
        if (update == 0 || type == 0) {
            return 0;
        }
        long expires = (long) memory.readInt(type + hbtDuration) + memory.readInt(update + hueCycleOffset)
                + memory.readInt(update + hueCycle);
        return expires > cycle ? update : 0;
    }

    /** The NPC's base type struct (what {@code Npc.type} points at), or 0. */
    public long npcTypeStruct(long npc) {
        return npc == 0 ? 0 : safePointer(npc + npcType);
    }

    /** {@code NpcType.id} of the NPC's base type, or -1. */
    public int npcTypeId(long npc) {
        long type = npcTypeStruct(npc);
        return type == 0 ? -1 : safeInt(type + ntId, -1);
    }

    /**
     * {@link #npcTypeId}, read strictly for the NPC change events: a type pointer that is 0 (the client clears it as
     * it removes the NPC) or can't be read gives null rather than -1.
     *
     * @param npc the NPC's address
     * @return the base type's id; null if it can't be read
     */
    public Integer readNpcTypeId(long npc) {
        if (npc == 0) {
            return null;
        }
        try {
            long type = memory.readPointer(npc + npcType);
            return type == 0 ? null : memory.readInt(type + ntId);
        } catch (MemoryException e) {
            return null;
        }
    }

    /**
     * The NPC's name as the client shows it: its own name if it has one, otherwise its type's name, following the
     * type's multi-NPC transform (a varbit or varp picks which child type it currently is) when it has one.
     */
    public String npcName(long npc) {
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
            NPCComposition transformed = configs.npc(child);
            if (transformed != null) {
                return transformed.getName();
            }
        }
        return structs.string(type + ntName);
    }

    /**
     * The type the NPC currently shows: the multi-NPC child its base type's varbit or varp picks, or the base type
     * itself when it has no transform.
     *
     * @param npc the NPC's address
     * @return the shown type's id; -1 if the transform picks no type, or if the NPC isn't loaded
     */
    public int npcShownTypeId(long npc) {
        long type = npcTypeStruct(npc);
        if (type == 0) {
            return -1;
        }
        return hasMultiNpc(type) ? multiNpcChild(type) : safeInt(type + ntId, -1);
    }

    /**
     * Whether a type has a multi-NPC transform the client would follow.
     *
     * @param type the NpcType struct's address
     * @return true if the type has a readable list of child types
     */
    private boolean hasMultiNpc(long type) {
        if (ntMultiCount < 0 || ntMulti < 0 || ntMultiVarbit < 0 || ntMultiVarp < 0) {
            return false;
        }
        long count = safeLong(type + ntMultiCount, 0);
        return count > 0 && count <= 1024 && safePointer(type + ntMulti) != 0;
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
    public int npcCategory(long npc) {
        long type = npcTypeStruct(npc);
        return type == 0 || ntCategory < 0 ? -1 : safeInt(type + ntCategory, -1);
    }

    public boolean npcIsFollower(long npc) {
        long type = npcTypeStruct(npc);
        return type != 0 && ntIsFollower >= 0 && safeU8(type + ntIsFollower, 0) != 0;
    }

    /**
     * {@code isLocalFollower}: one of the varps the client tracks as follower slots (the server marks them; the client
     * collects their ids) holds this NPC's index in its low 16 bits.
     */
    public boolean npcIsLocalFollower(int index) {
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
    public int npcUid(long npc) {
        long type = npcTypeStruct(npc);
        if (type == 0 || npcIndex < 0) {
            return -1;
        }
        return safeInt(type + ntId, 0) * 0x10000 + safeInt(npc + npcIndex, 0);
    }

    /** {@code npcCreationCycle}: the client cycle the NPC was created on, or -1. */
    public int npcCreationCycle(long npc) {
        return npc == 0 || npcSpawnCycle < 0 ? -1 : safeInt(npc + npcSpawnCycle, -1);
    }

    /** The player's display name (the client cuts it at the first NUL), or "". */
    public String playerName(long player) {
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
    public boolean playerIsOnlineFriend(long player) {
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
    public boolean playerIsFriendChat(long player) {
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
    public boolean playerIsGroup(long player) {
        return cachedState(player, pGroupState) == 1;
    }

    /** {@code isIgnored}: the player's name (current or previous) is on the ignore list. */
    public boolean playerIsIgnored(long player) {
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
     * Whether the client is on a members world: bit 0 of the world-type flags. The item loader tests a byte the client
     * sets from the same world record bit, so this picks the same item rewrites.
     */
    public boolean membersWorld() {
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
        VarbitComposition type = configs.varbit(varbit);
        if (type == null) {
            return -1;
        }
        int varp = varpBits(type.getIndex());
        return varp == Integer.MIN_VALUE ? -1 : type.extract(varp);
    }
}
