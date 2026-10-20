package com.kraken.api.lua;

import com.kraken.api.lua.types.ConfigTypes;
import com.kraken.api.lua.types.InvType;
import com.kraken.api.lua.types.LocType;
import com.kraken.api.lua.types.NpcType;
import com.kraken.api.lua.types.ObjType;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mirrors the client's Lua {@code osrs.ClientOp} namespace: players, NPCs, inventories and projectiles, reproduced by
 * following the mapped offset chains in the live process. (Client-wide values such as stats, vars and the tick are in
 * {@link ClientState}, as they are in Lua.)
 *
 * <p>Actor functions taking an index ({@code npcName(id)}, {@code playerCoord(id)}…) search every loaded world, the
 * main one and sub-worlds such as boats, as the client's do; {@link #getNpcObj}/{@link #getPlayerObj} handles only
 * resolve in the top-level world, like the Lua objects. Inventories live in a separate global hash table keyed by
 * inventory id. Reads are tolerant: an unreadable or logged-out state yields empty results (empty arrays, "", null,
 * -1) rather than exceptions.
 *
 * <p>Naming note: player names live behind a pointer at {@code Player+0x718} (the pointed-to object holds the display
 * name and the normalised name), whereas an NPC's own name is the inline string at {@code Npc+0x710}, falling back to
 * its type's name.
 *
 * <p>Coordinates ({@link MapCoord}) are scene tiles: relative to the loaded map's base, as the client stores them
 * ({@link Scene#getBase()} turns them into world tiles).
 *
 * <p>Like the Lua functions, {@link #locFind} and {@link #objFind} make what they find the <em>active</em> loc or obj,
 * which the no-argument {@code loc*}/{@code obj*} functions then read. The client keeps one active loc and obj for its
 * Lua plugins; this reader keeps its own (one per {@code ClientOp}), so the two never disturb each other.
 */
public final class ClientOp extends GameReader {

    private static final int MAX_PLAYERS = 2048;
    private static final int MAX_NPCS = 65536;
    private static final long MAX_BUCKETS = 1 << 16;
    private static final int MAX_CHAIN = 10_000;
    private static final int MAX_SLOTS = 4096;

    /**
     * Mirrors {@code ClientOp.WornSlots}: the worn-equipment inventory's (94) slot for each body part.
     */
    public enum WornSlots {
        HEAD(0), CAPE(1), AMULET(2), WEAPON(3), BODY(4), SHIELD(5), LEGS(7), GLOVES(9), BOOTS(10), RING(12), AMMO(13);

        private final int slot;

        WornSlots(int slot) {
            this.slot = slot;
        }

        public int slot() {
            return slot;
        }

        /**
         * The worn slot with a slot index.
         *
         * @param slot the slot index in the worn-equipment inventory
         * @return the body part's worn slot, or null for an index no body part uses
         */
        public static WornSlots of(int slot) {
            for (WornSlots s : values()) {
                if (s.slot == slot) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * The backpack's inventory id.
     */
    public static final int INV_BACKPACK = 93;

    /**
     * The worn-equipment inventory's id; see {@link WornSlots}.
     */
    public static final int INV_WORN = 94;

    /**
     * The bank's inventory id.
     */
    public static final int INV_BANK = 95;

    private final ActorReader actors;
    private final SceneReader scene;
    private final ProjectileReader projectiles;
    private final ConfigTypes configs;

    private final int cPlayerCount;
    private final int cPlayerIndices;
    private final int wNpcIndices;
    private final int wNpcCount;

    private final long invBucketsRva;
    private final long invBucketCountRva;
    private final int invId;
    private final int invNext;
    private final int invIds;
    private final int invIdsEnd;
    private final int invCounts;
    private final int invCountsEnd;
    private final int invNodeSize;

    public ClientOp(Memory memory, Mapping mapping) {
        this(memory, mapping, new ConfigTypes(memory, mapping));
    }

    /**
     * Creates the reader, sharing {@code configs} (and its cache) with the other readers.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     * @param configs the config type reader to decode item, NPC, loc and inventory definitions with
     */
    public ClientOp(Memory memory, Mapping mapping, ConfigTypes configs) {
        super(memory, mapping);
        this.configs = configs;
        this.actors = new ActorReader(memory, mapping, configs);
        this.scene = new SceneReader(memory, mapping, configs, actors);
        this.projectiles = new ProjectileReader(memory, mapping);

        this.cPlayerCount = mapping.offset("Client.playerIndexCount");
        this.cPlayerIndices = mapping.offset("Client.playerIndices");
        this.wNpcIndices = mapping.offset("World.npcIndices");
        this.wNpcCount = mapping.offset("World.npcIndexCount");

        this.invBucketsRva = mapping.global("inventories.buckets");
        this.invBucketCountRva = mapping.global("inventories.bucketCount");
        this.invId = mapping.offset("Inventory.id");
        this.invNext = mapping.offset("Inventory.next");
        this.invIds = mapping.offset("Inventory.ids");
        this.invIdsEnd = mapping.offset("Inventory.idsEnd");
        this.invCounts = mapping.offset("Inventory.counts");
        this.invCountsEnd = mapping.offset("Inventory.countsEnd");
        this.invNodeSize = 8 + Collections.max(List.of(invId, invNext, invIds, invIdsEnd, invCounts, invCountsEnd));
    }

    /**
     * The config types this reader decodes, shared with the other readers. Not a Lua function.
     *
     * @return the shared config type reader
     */
    public ConfigTypes configs() {
        return configs;
    }

    /**
     * Index of the local player.
     *
     * @return the local player's index, or -1 when logged out or loading
     */
    public int getLocalPlayerIndex() {
        return actors.localPlayerIndex();
    }

    /**
     * Mirrors {@code ClientOp.playerFindSelf}. (Lua returns {@code [found, id]}.)
     *
     * @return the local player's index, or -1 if there isn't one
     */
    public int playerFindSelf() {
        return getLocalPlayerIndex();
    }

    /**
     * Mirrors {@code ClientOp.coord}: the local player's tile. The plane is the player's own plane field, which is what
     * the client returns here.
     *
     * @return the local player's tile, or null if there's no local player
     */
    public MapCoord coord() {
        return actors.localCoord();
    }

    /**
     * Mirrors {@code ClientOp.coordFine}: the local player's fine position and the ground height there.
     *
     * @return the fine position, or {@link FineCoord#MISSING} if there's no local player or its world isn't loaded
     */
    public FineCoord coordFine() {
        return actors.localFineCoord();
    }

    /**
     * The local player's handle. Not a Lua function.
     *
     * @return a live handle on the local player, or null when logged out
     */
    public Player getLocalPlayer() {
        int index = getLocalPlayerIndex();
        return index < 0 ? null : getPlayerObj(index);
    }

    /**
     * Mirrors {@code ClientOp.getPlayerIdAll}: indices of every loaded player (the inline index array in the Client).
     *
     * @return the player indices, empty when logged out
     */
    public int[] getPlayerIdAll() {
        long client = clientBase();
        if (client == 0) {
            return new int[0];
        }
        int count = safeInt(client + cPlayerCount, 0);
        return readIndexArray(client + cPlayerIndices, count, MAX_PLAYERS);
    }

    /**
     * Mirrors {@code ClientOp.playerName}.
     *
     * @param playerId the player's index
     * @return the player's display name, or "" if not found
     */
    public String playerName(int playerId) {
        return actors.playerName(actors.playerInAnyWorld(playerId));
    }

    /**
     * Mirrors {@code ClientOp.playerCoord}.
     *
     * @param playerId the player's index
     * @return the player's tile on its world's plane, or null if not found
     */
    public MapCoord playerCoord(int playerId) {
        return actors.coord(actors.playerInAnyWorld(playerId));
    }

    /**
     * Mirrors {@code ClientOp.playerCoordFine} (the docs call it {@code playerFineCoord}; the client registers
     * {@code playerCoordFine}): the player's fine position and the ground height there.
     *
     * @param playerId the player's index
     * @return the fine position, or {@link FineCoord#MISSING} if the player or its world isn't loaded
     */
    public FineCoord playerCoordFine(int playerId) {
        return actors.fineCoord(actors.playerInAnyWorld(playerId));
    }

    /**
     * Mirrors {@code ClientOp.playerRouteLength}. As the Lua docs warn, it isn't the length of the whole route.
     *
     * @param playerId the player's index
     * @return how many steps the player has queued on its path; 0 when standing still or not found
     */
    public int playerRouteLength(int playerId) {
        return actors.routeLength(actors.playerInAnyWorld(playerId));
    }

    /**
     * Mirrors {@code ClientOp.getPlayerIdByName}. Matching is on the normalised name against {@code name} lower-cased,
     * like the client.
     *
     * @param name the player's name
     * @return the index of the first loaded player whose name matches, or -1
     */
    public int getPlayerIdByName(String name) {
        return actors.playerIdByName(name);
    }

    /**
     * Mirrors {@code ClientOp.getPlayerObj}.
     *
     * @param playerId the player's index
     * @return a live handle on the player, or null if there's no player at that index in the top-level world
     */
    public Player getPlayerObj(int playerId) {
        return actors.player(playerId) == 0 ? null : new Player(actors, playerId);
    }

    /**
     * A handle pinned to the player at an index now. Unlike {@link #getPlayerObj}'s handle, it doesn't follow the
     * index to the next player the client puts there: once this player is gone it reads as not loaded. The pin is the
     * player's address and name, since a new player can be allocated where the old one was. Not a Lua function; the
     * RuneLite-shaped {@code Player} is built on it.
     *
     * @param playerId the player's index
     * @return a handle on the player now at that index in the top-level world, or null if there is none
     */
    public Player pinPlayer(int playerId) {
        long address = actors.player(playerId);
        return address == 0 ? null : new Player(actors, playerId, address, actors.playerName(address));
    }

    /**
     * A handle on every loaded player. Not a Lua function.
     *
     * @return live handles on the loaded players, empty when logged out
     */
    public List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();
        for (int index : getPlayerIdAll()) {
            Player player = getPlayerObj(index);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * Mirrors {@code ClientOp.getNpcIdAll}: indices of every loaded NPC in the current world.
     *
     * @return the NPC indices, empty when logged out
     */
    public int[] getNpcIdAll() {
        long world = actors.topWorld();
        if (world == 0) {
            return new int[0];
        }
        int count = safeInt(world + wNpcCount, 0);
        long base = safePointer(world + wNpcIndices);
        return readIndexArray(base, count, MAX_NPCS);
    }

    /**
     * Mirrors {@code ClientOp.npcName}: the NPC's own name if set, otherwise its type's name, following the type's
     * multi-NPC transform when it has one (as the client does).
     *
     * @param npcId the NPC's index
     * @return the NPC's name, or "" if not found
     */
    public String npcName(int npcId) {
        return actors.npcName(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.npcTypeId}.
     *
     * @param npcId the NPC's index
     * @return the NPC's base type id (the wiki id), or -1 if not found
     */
    public int npcTypeId(int npcId) {
        return actors.npcTypeId(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.npcCoord}.
     *
     * @param npcId the NPC's index
     * @return the NPC's tile on its world's plane, or null if not found
     */
    public MapCoord npcCoord(int npcId) {
        return actors.coord(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.npcCoordFine}: the NPC's fine position and the ground height there.
     *
     * @param npcId the NPC's index
     * @return the fine position, or {@link FineCoord#MISSING} if the NPC or its world isn't loaded
     */
    public FineCoord npcCoordFine(int npcId) {
        return actors.fineCoord(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.npcUid}.
     *
     * @param npcId the NPC's index
     * @return the NPC's type id × 65536 + its index, or -1 if not found
     */
    public int npcUid(int npcId) {
        return actors.npcUid(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.npcCreationCycle}.
     *
     * @param npcId the NPC's index
     * @return the client cycle (20 ms units) the NPC was created on, or -1 if not found
     */
    public int npcCreationCycle(int npcId) {
        return actors.npcCreationCycle(actors.npcInAnyWorld(npcId));
    }

    /**
     * Mirrors {@code ClientOp.getNpcIdByName}. Differs from the client on purpose: the native handler looks its match
     * up again by uid instead of index, so in Lua it returns -1 for almost every NPC.
     *
     * @param name the exact name to match (case-sensitive)
     * @return the index of the first NPC with that name, or -1
     */
    public int getNpcIdByName(String name) {
        return actors.npcIdByName(name);
    }

    /**
     * Mirrors {@code ClientOp.getNpcIdByNameAll}. ("Rendered on the screen" in the Lua docs means the client's active
     * NPC list.)
     *
     * @param name the exact name to match (case-sensitive)
     * @return the indices of every active NPC with that name, empty if none
     */
    public int[] getNpcIdByNameAll(String name) {
        return actors.npcIdsByName(name);
    }

    /**
     * Mirrors {@code ClientOp.getNpcObj}.
     *
     * @param npcId the NPC's index
     * @return a live handle on the NPC, or null if there's no NPC at that index in the top-level world
     */
    public Npc getNpcObj(int npcId) {
        return actors.npc(npcId) == 0 ? null : new Npc(actors, npcId);
    }

    /**
     * A handle pinned to the NPC at an index now. Unlike {@link #getNpcObj}'s handle, it doesn't follow the index to
     * the next NPC the client puts there: once this NPC is gone it reads as not loaded. The pin is the NPC's address
     * and creation cycle, since a new NPC can be allocated where the old one was. Not a Lua function; the
     * RuneLite-shaped {@code NPC} is built on it.
     *
     * @param npcId the NPC's index
     * @return a handle on the NPC now at that index in the top-level world, or null if there is none
     */
    public Npc pinNpc(int npcId) {
        long address = actors.npc(npcId);
        return address == 0 ? null : new Npc(actors, npcId, address, actors.npcCreationCycle(address));
    }

    /**
     * A handle on every loaded NPC. Not a Lua function.
     *
     * @return live handles on the loaded NPCs, empty when logged out
     */
    public List<Npc> getNpcs() {
        List<Npc> npcs = new ArrayList<>();
        for (int index : getNpcIdAll()) {
            Npc npc = getNpcObj(index);
            if (npc != null) {
                npcs.add(npc);
            }
        }
        return npcs;
    }

    /**
     * Mirrors {@code ClientOp.areProjectilesRendered}. The docs say it checks that all projectiles are drawn; the
     * handler returns true as soon as one projectile in flight is drawn (doesn't carry the hidden flag that
     * {@code renderProjectiles(false)} sets), and false when none are in flight. This does the same.
     *
     * @return true if any projectile in flight is drawn; false if none is, or nothing is in flight
     */
    public boolean areProjectilesRendered() {
        return projectiles.anyRendered();
    }

    /**
     * Every projectile in flight, in the client's list order. Not a Lua function.
     *
     * @return one snapshot per projectile; empty when nothing is in flight, when logged out, or with a mapping that
     *         doesn't have the projectile keys
     */
    public List<Projectile> getProjectiles() {
        return projectiles.projectiles();
    }

    /**
     * An item's definition as the client loads it (noted and placeholder items merged with the items they stand for,
     * members items rewritten on a free world). Not a Lua function; {@code Engine.decodeObjType} returns the same.
     *
     * @param objId the item id
     * @return the definition (the client's default type for an id without one), or null if config types can't be read
     */
    public ObjType getObjType(int objId) {
        return configs.obj(objId, actors.membersWorld());
    }

    /**
     * Mirrors {@code ClientOp.ocName}. On a free world, members items read "&lt;name&gt; (Members)" as in the client.
     *
     * @param objId the item id
     * @return the item's name; "" for an id without a definition, or if config types can't be read
     */
    public String ocName(int objId) {
        ObjType type = getObjType(objId);
        return type == null ? "" : type.getName();
    }

    /**
     * Mirrors {@code ClientOp.ocCost}.
     *
     * @param objId the item id
     * @return the item's value (store price); 1 for an id without a definition, as in the client; -1 if config types
     *         can't be read
     */
    public int ocCost(int objId) {
        ObjType type = getObjType(objId);
        return type == null ? -1 : type.getValue();
    }

    /**
     * Mirrors {@code ClientOp.ocStackable}.
     *
     * @param objId the item id
     * @return true if the item stacks; false if it doesn't, or if config types can't be read
     */
    public boolean ocStackable(int objId) {
        ObjType type = getObjType(objId);
        return type != null && type.isStackable();
    }

    /**
     * Mirrors {@code ClientOp.ocOp}: one of the item's ground ops, with conditional ops resolved against the current
     * vars like the client does.
     *
     * @param objId the item id
     * @param index the op, 1-based as in Lua (3 is usually "Take")
     * @return the op's text; "" for an index the item doesn't have, or if config types can't be read
     */
    public String ocOp(int objId, int index) {
        ObjType type = getObjType(objId);
        return type == null ? "" : type.groundOp(index - 1, actors.vars);
    }

    /**
     * Mirrors {@code ClientOp.ncName} (bound by the client, though not in the Lua docs): an NPC type's name. Compare
     * {@link #npcName}, which takes a loaded NPC's index.
     *
     * @param npcTypeId the NPC type id
     * @return the type's name; "" for an id without a definition, or if config types can't be read
     */
    public String ncName(int npcTypeId) {
        NpcType type = configs.npc(npcTypeId);
        return type == null ? "" : type.getName();
    }

    /**
     * The var state the client resolves conditional ops against (varps from the copy varbits read, varbits through
     * their definitions). Pass it to {@link ObjType#getIOps(ObjType.VarReader)}, or as
     * {@code type.getOps(client.vars()::varp, client.vars()::varbit)} for NPC and loc types. Not a Lua function.
     *
     * @return a reader of the live varps and varbits (-1 for anything unreadable)
     */
    public ObjType.VarReader vars() {
        return actors.vars;
    }

    /**
     * The reader {@link Scene}, {@link World} and {@link Collision} share with this one.
     *
     * @return the scene reader
     */
    SceneReader sceneReader() {
        return scene;
    }

    /**
     * The actor reader the scene facades use for ground heights.
     *
     * @return the actor reader
     */
    ActorReader actorReader() {
        return actors;
    }

    /**
     * What {@link #locFind} found: the loc's type, its tile (as bytes, in the world it was found in), its layer, and
     * the world coordinate it was searched at.
     */
    private record ActiveLoc(int type, int level, int x, int z, int layer, int worldX, int worldZ) {
    }

    /**
     * What {@link #objFind} found: the pile entry (the client holds a weak reference to it), where its pile is, and the
     * world coordinate it was searched at.
     */
    private record ActiveObj(long item, long world, int level, int x, int z, int worldX, int worldZ) {
    }

    private volatile ActiveLoc activeLoc;
    private volatile ActiveObj activeObj;

    /**
     * Mirrors {@code ClientOp.locFind}: looks for a loc of a type on a tile and, if there is one, makes it the active
     * loc for {@link #locCoord}, {@link #locName} and the other {@code loc*} functions. Like the client, it turns the
     * scene coordinate into a world coordinate, finds the world holding it (a boat's, or the top-level world), and
     * checks that tile's wall decoration, wall, ground decoration and game object, in that order. A miss leaves the
     * previous active loc in place.
     *
     * @param type  the loc type id
     * @param coord the tile, in scene coordinates (as {@link #coord()} returns them)
     * @return true if the tile has such a loc
     */
    public boolean locFind(int type, MapCoord coord) {
        int[] at = coord == null ? null : scene.toWorld(coord);
        long world = at == null ? 0 : scene.worldAt(at[1], at[2]);
        int[] base = scene.worldBase(world);
        if (base == null) {
            return false;
        }
        int x = at[1] - base[0];
        int z = at[2] - base[1];
        if (!scene.inWorld(world, at[0], x, z)) {
            return false;
        }
        int layer = scene.findLoc(scene.sceneOf(world), at[0], x, z, type);
        if (layer < 0) {
            return false;
        }
        activeLoc = new ActiveLoc(type, at[0] & 0xff, x & 0xff, z & 0xff, layer, at[1], at[2]);
        return true;
    }

    /**
     * Makes a loc the active one without searching for it, as the client does before it calls
     * {@code ON_LOC_LOAD}/{@code ON_LOC_UNLOAD} subscribers (the loc may already be gone). Not a Lua function.
     *
     * @param loc a loc of the top-level scene, e.g. from {@link Scene#getLocs}
     */
    public void activateLoc(Loc loc) {
        long world = scene.topWorld();
        int[] base = scene.worldBase(world);
        if (loc == null || base == null) {
            return;
        }
        MapCoord at = loc.coord();
        activeLoc = new ActiveLoc(loc.id(), at.level() & 0xff, at.x() & 0xff, at.z() & 0xff, loc.layer(),
                at.x() + base[0], at.z() + base[1]);
    }

    /**
     * Mirrors {@code ClientOp.locCoord}: the active loc's tile. The level is one lower for a tile under a bridge, as
     * the client reports it.
     *
     * @return the tile in scene coordinates (of the world it is in), or null if no loc is active or its world isn't
     *         loaded any more
     */
    public MapCoord locCoord() {
        ActiveLoc loc = activeLoc;
        if (loc == null) {
            return null;
        }
        long world = scene.worldAt(loc.worldX(), loc.worldZ());
        long sc = scene.sceneOf(world);
        if (sc == 0) {
            return null;
        }
        return new MapCoord(scene.visibleLevel(sc, loc.level(), loc.x(), loc.z()) & 3, loc.x(), loc.z());
    }

    /**
     * Mirrors {@code ClientOp.locName}: the active loc's name, from its type.
     *
     * @return the name, or "" if no loc is active or config types can't be read
     */
    public String locName() {
        LocType type = activeLocType();
        return type == null ? "" : type.getName();
    }

    /**
     * Mirrors {@code ClientOp.locTypeId}.
     *
     * @return the active loc's type id, or -1 if no loc is active
     */
    public int locTypeId() {
        ActiveLoc loc = activeLoc;
        return loc == null ? -1 : loc.type();
    }

    /**
     * Mirrors {@code ClientOp.locGetOp}: one of the active loc's ops, with conditional ops resolved against the
     * current vars like the client does.
     *
     * @param includeTags 0 to strip the op's markup ({@code <col=…>} and friends; {@code <lt>}, {@code <gt>},
     *                    {@code <at>} become {@code <}, {@code >}, {@code @} and {@code <br>} a newline), anything else
     *                    to return it as stored
     * @param opNumber    the op, 1-based as in Lua
     * @return the op's text; "" for an op the loc doesn't have, or if no loc is active
     */
    public String locGetOp(int includeTags, int opNumber) {
        LocType type = activeLocType();
        if (type == null || opNumber < 1 || opNumber > type.getOps().size()) {
            return "";
        }
        String op = type.getOps(actors.vars::varp, actors.vars::varbit).get(opNumber - 1);
        return includeTags != 0 ? op : stripTags(op);
    }

    /**
     * Mirrors {@code ClientOp.locWidth}: the active loc's size along x, from its type (not turned for its rotation).
     *
     * @return the width in tiles, or -1 if no loc is active (Lua gets nothing back)
     */
    public int locWidth() {
        LocType type = activeLocType();
        return type == null ? -1 : type.getWidth();
    }

    /**
     * Mirrors {@code ClientOp.locLength}: the active loc's size along z, from its type (not turned for its rotation).
     *
     * @return the length in tiles, or -1 if no loc is active (Lua gets nothing back)
     */
    public int locLength() {
        LocType type = activeLocType();
        return type == null ? -1 : type.getLength();
    }

    private LocType activeLocType() {
        ActiveLoc loc = activeLoc;
        return loc == null ? null : configs.loc(loc.type());
    }

    /**
     * Mirrors {@code ClientOp.objFind}: looks for an item of a type in a tile's pile and, if there is one, makes it
     * the active obj for {@link #objCoord}, {@link #objCount} and the other {@code obj*} functions. The first matching
     * entry in the pile's order wins. A miss leaves the previous active obj in place.
     *
     * @param type  the item id
     * @param coord the tile, in scene coordinates (as {@link #coord()} returns them)
     * @return true if the tile has such an item
     */
    public boolean objFind(int type, MapCoord coord) {
        int[] at = coord == null ? null : scene.toWorld(coord);
        int[] base = scene.mapBase();
        long world = at == null ? 0 : scene.worldAt(at[1], at[2]);
        if (world == 0 || base == null) {
            return false;
        }
        int x = at[1] - base[0];
        int z = at[2] - base[1];
        if (!scene.inWorld(world, at[0], x, z)) {
            return false;
        }
        for (long item : scene.pileItems(world, at[0], x, z)) {
            if (scene.itemId(item) == type) {
                activeObj = new ActiveObj(item, world, at[0], x, z, at[1], at[2]);
                return true;
            }
        }
        return false;
    }

    /**
     * Mirrors {@code ClientOp.objCoord}: the active obj's tile. The level is reported as the client does: 0 for a tile
     * whose settings have bit 3 set, else one lower under a bridge.
     *
     * @return the tile in scene coordinates (of the world it is in), or null if no obj is active or its world isn't
     *         loaded any more
     */
    public MapCoord objCoord() {
        ActiveObj obj = activeObj;
        if (obj == null) {
            return null;
        }
        long world = scene.worldAt(obj.worldX(), obj.worldZ());
        int[] base = scene.worldBase(world);
        long sc = scene.sceneOf(world);
        if (base == null || sc == 0) {
            return null;
        }
        int x = obj.worldX() - base[0];
        int z = obj.worldZ() - base[1];
        return new MapCoord(scene.objLevel(sc, obj.level(), x, z) & 3, x, z);
    }

    /**
     * Mirrors {@code ClientOp.objName}: the active obj's name, from its type, as {@link #ocName} gives it.
     *
     * @return the name, or "" if no obj is active, it has left the ground, or config types can't be read
     */
    public String objName() {
        long item = liveObj();
        return item == 0 ? "" : ocName(scene.itemId(item));
    }

    /**
     * Mirrors {@code ClientOp.objTypeId}.
     *
     * @return the active obj's item id, or -1 if no obj is active or it has left the ground
     */
    public int objTypeId() {
        long item = liveObj();
        return item == 0 ? -1 : scene.itemId(item);
    }

    /**
     * Mirrors {@code ClientOp.objCount}: the active obj's stack size, read live (it follows the stack as it changes).
     *
     * @return the count, or -1 if no obj is active or it has left the ground
     */
    public int objCount() {
        long item = liveObj();
        return item == 0 ? -1 : scene.itemCount(item);
    }

    /**
     * Mirrors {@code ClientOp.objGetOp}: one of the active obj's ground ops, with conditional ops resolved like
     * {@link #ocOp}.
     *
     * @param includeTags 0 to strip the op's markup (see {@link #locGetOp}), anything else to return it as stored
     * @param opNumber    the op, 1-based as in Lua (3 is usually "Take")
     * @return the op's text; "" for an op the item doesn't have, if no obj is active or it has left the ground
     */
    public String objGetOp(int includeTags, int opNumber) {
        long item = liveObj();
        if (item == 0) {
            return "";
        }
        String op = ocOp(scene.itemId(item), opNumber);
        return includeTags != 0 ? op : stripTags(op);
    }

    /**
     * The active obj's pile entry if it is still on the ground. The client keeps a weak reference to the entry, which
     * dies when the item leaves the pile; this checks the entry is still in its tile's pile instead.
     *
     * @return the entry's address, or 0 if no obj is active or it has gone
     */
    private long liveObj() {
        ActiveObj obj = activeObj;
        if (obj == null) {
            return 0;
        }
        for (long item : scene.pileItems(obj.world(), obj.level(), obj.x(), obj.z())) {
            if (item == obj.item()) {
                return item;
            }
        }
        return 0;
    }

    /**
     * The client's op text cleanup for {@code locGetOp}/{@code objGetOp} with tags excluded: text between tags is kept,
     * {@code <lt>}, {@code <gt>} and {@code <at>} become {@code <}, {@code >} and {@code @}, {@code <br>} and
     * {@code <n>} become a newline, and any other tag is dropped. Replays the client's loop, including its handling of
     * an unclosed {@code <} (the text before it is written twice).
     *
     * @param text the op text as stored
     * @return the text without markup
     */
    static String stripTags(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int segment = 0;
        int tag = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                out.append(text, segment, i);
                tag = i;
            } else if (c == '>' && tag != -1) {
                switch (text.substring(tag + 1, i)) {
                    case "lt" -> out.append('<');
                    case "gt" -> out.append('>');
                    case "at" -> out.append('@');
                    case "br", "n" -> out.append('\n');
                    default -> {
                    }
                }
                tag = -1;
                segment = i + 1;
            }
        }
        if (segment < text.length()) {
            out.append(text, segment, text.length());
        }
        return out.toString();
    }

    /**
     * Mirrors {@code ClientOp.invSize}: how many slots the inventory has, from its definition, whether or not the
     * client holds the inventory now.
     *
     * @param invId the inventory id, e.g. {@link #INV_BACKPACK}
     * @return the slot count; 0 for an id without a definition, as in the client; -1 if config types can't be read
     */
    public int invSize(int invId) {
        InvType type = configs.inv(invId);
        return type == null ? -1 : type.size();
    }

    /**
     * Mirrors {@code ClientOp.invGetObjId}.
     *
     * @param invId the inventory id, e.g. {@link #INV_BACKPACK}
     * @param slot  the slot, from 0
     * @return the item id in the slot, or -1 if the slot is empty, past the end of what the client holds, or the
     *         inventory isn't loaded
     */
    public int invGetObjId(int invId, int slot) {
        long node = inventoryNode(invId);
        if (node == 0 || slot < 0) {
            return -1;
        }
        long begin = safePointer(node + invIds);
        long length = (safePointer(node + invIdsEnd) - begin) >> 2;
        return slot < length ? safeInt(begin + slot * 4L, -1) : -1;
    }

    /**
     * Mirrors {@code ClientOp.invGetNum}. Like the native handler, this is 0 (not the -1 the Lua docs mention) when
     * there's nothing to report.
     *
     * @param invId the inventory id, e.g. {@link #INV_BACKPACK}
     * @param slot  the slot, from 0
     * @return the stack size in the slot; 0 if the slot is empty or out of range or the inventory isn't loaded
     */
    public int invGetNum(int invId, int slot) {
        long node = inventoryNode(invId);
        if (node == 0 || slot < 0) {
            return 0;
        }
        long begin = safePointer(node + invCounts);
        long length = (safePointer(node + invCountsEnd) - begin) >> 2;
        return slot < length ? safeInt(begin + slot * 4L, 0) : 0;
    }

    /**
     * Mirrors {@code ClientOp.invTotal}: how many of an item an inventory holds across all its slots.
     *
     * @param invId the inventory id, e.g. {@link #INV_BACKPACK}
     * @param objId the item id
     * @return the total count; 0 if none, if the inventory isn't loaded, or for item id -1
     */
    public int invTotal(int invId, int objId) {
        if (objId == -1) {
            return 0;
        }
        Inventory inventory = getInventory(invId);
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (InvItem item : inventory.items()) {
            if (item.id() == objId) {
                total += item.count();
            }
        }
        return total;
    }

    /**
     * Every slot the client holds for an inventory. Not a Lua function: a convenience over
     * {@link #invGetObjId}/{@link #invGetNum}.
     *
     * <p>The client grows an inventory's slot arrays up to the highest slot the server has filled, so this can be
     * shorter than the inventory's defined size ({@link #invSize}, which comes from the inventory's type).
     *
     * @param invId the inventory id, e.g. {@link #INV_BACKPACK}
     * @return the inventory's slots, including empty ones (id -1, count 0), or null if it isn't loaded
     */
    public Inventory getInventory(int invId) {
        long node = inventoryNode(invId);
        return node == 0 ? null : readInventory(node);
    }

    /**
     * Every inventory the client currently holds (backpack, worn equipment, bank once opened, shops…). Not a Lua
     * function: the dev tools and the {@code ON_INV_TRANSMIT} event use it.
     *
     * @return the inventories in no particular order, empty if the table can't be read
     */
    public List<Inventory> getInventories() {
        long base = memory.moduleBase();
        long buckets = safePointer(base + invBucketsRva);
        long count = safeLong(base + invBucketCountRva, 0) & 0xffffffffL; // the client divides by the low 32 bits
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return List.of();
        }
        ByteBuffer heads = readBlock(buckets, (int) count * 8);
        if (heads == null) {
            return List.of();
        }
        List<Inventory> inventories = new ArrayList<>();
        int guard = 0;
        for (int b = 0; b < count; b++) {
            long node = heads.getLong(b * 8);
            while (node != 0 && guard++ < MAX_CHAIN) {
                Inventory inventory = readInventory(node);
                if (inventory == null) {
                    break;
                }
                inventories.add(inventory);
                node = safePointer(node + invNext);
            }
        }
        return inventories;
    }

    /**
     * The inventory table's node for an id. Same walk as the client's own lookup: the bucket is the sign-extended id
     * modulo the (32-bit) bucket count, then the chain is followed through {@code Inventory.next}.
     *
     * @param id the inventory id
     * @return the node's address, or 0 if the inventory isn't loaded
     */
    private long inventoryNode(int id) {
        long base = memory.moduleBase();
        long buckets = safePointer(base + invBucketsRva);
        long count = safeLong(base + invBucketCountRva, 0) & 0xffffffffL;
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return 0;
        }
        long node = safePointer(buckets + Long.remainderUnsigned(id, count) * 8);
        for (int guard = 0; node != 0 && guard < MAX_CHAIN; guard++) {
            if (safeInt(node + invId, Integer.MIN_VALUE) == id) {
                return node;
            }
            node = safePointer(node + invNext);
        }
        return 0;
    }

    private Inventory readInventory(long node) {
        ByteBuffer n = readBlock(node, invNodeSize);
        if (n == null) {
            return null;
        }
        int id = n.getInt(invId);
        int[] ids = readVector(n.getLong(invIds), n.getLong(invIdsEnd));
        int[] counts = readVector(n.getLong(invCounts), n.getLong(invCountsEnd));
        if (ids == null || counts == null) {
            return null;
        }
        List<InvItem> items = new ArrayList<>(ids.length);
        for (int slot = 0; slot < ids.length; slot++) {
            items.add(new InvItem(ids[slot], slot, slot < counts.length ? counts[slot] : 0));
        }
        return new Inventory(id, List.copyOf(items));
    }

    /**
     * An eastl::vector&lt;int&gt; given its begin/end pointers.
     *
     * @param begin address of the first element
     * @param end   address just past the last element
     * @return the elements, or null if the vector looks corrupt or can't be read
     */
    private int[] readVector(long begin, long end) {
        long length = (end - begin) >> 2;
        if (length < 0 || length > MAX_SLOTS || (length > 0 && begin == 0)) {
            return null;
        }
        return readInts(begin, (int) length);
    }

    private int[] readIndexArray(long base, int count, int limit) {
        if (base == 0 || count <= 0 || count > limit) {
            return new int[0];
        }
        int[] indices = readInts(base, count);
        return indices == null ? new int[0] : indices;
    }
}
