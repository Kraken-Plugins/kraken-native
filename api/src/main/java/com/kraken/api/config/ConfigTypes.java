package com.kraken.api.config;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Config type definitions (items, NPCs, locs, inventories, varbits), decoded from the client's in-memory config
 * archive the same way the client decodes them, and cached here.
 *
 * <p>Why decode instead of reading the client's own decoded types: the client keeps each type in an LRU cache of only
 * 64 entries, so most ids aren't there at any moment. The archive itself holds every file's bytes, and the bytes
 * never change while the client runs, so decoding them gives the same type for any id.
 *
 * <p>Lookups return the client's default type for an id with no file (what the client's loader would produce), and
 * null only when the archive can't be read at all (not attached yet, or a mapping without the archive layout).
 */
public final class ConfigTypes {

    private static final Logger log = Logger.getLogger("kraken.api");

    // Config group ids in the archive (cache index 2). These are the cache's own numbering, shared with every client
    // build; the updater names each type's loader after the group it reads (ConfigLoader.<group>).

    /**
     * Inventory types.
     */
    public static final int INV = 5;

    /**
     * Loc (scenery) types.
     */
    public static final int LOC = 6;

    /**
     * NPC types.
     */
    public static final int NPC = 9;

    /**
     * Obj (item) types.
     */
    public static final int OBJ = 10;

    /**
     * Varbit types.
     */
    public static final int VARBIT = 14;

    private static final int CACHE_SIZE = 8192;

    private final ConfigArchive archive;
    private final Map<Long, Object> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long cachedArchive;
    private Map<Integer, int[]> varbitsByVarp;
    private VarBitType[] varbitTable;

    /**
     * Creates the type reader; it reads nothing until asked for a type.
     *
     * @param memory  the client process
     * @param mapping the build's mapping (without the archive layout, every lookup returns null)
     */
    public ConfigTypes(Memory memory, Mapping mapping) {
        this.archive = ConfigArchive.supported(mapping) ? new ConfigArchive(memory, mapping) : null;
    }

    /**
     * Whether config types can be read: the mapping has the archive layout and the client has built the archive.
     *
     * @return true if lookups can decode types now
     */
    public boolean isAvailable() {
        return archive != null && archive.address() != 0;
    }

    /**
     * The raw archive, for callers that need file bytes.
     *
     * @return the archive reader, or null if the mapping doesn't have its layout
     */
    public ConfigArchive archive() {
        return archive;
    }

    /**
     * How many ids a config group has room for.
     *
     * @param group the config group, e.g. {@link #OBJ}
     * @return the highest id + 1, or -1 if the group can't be read
     */
    public int count(int group) {
        return archive == null ? -1 : archive.fileCount(group);
    }

    /**
     * A varbit's definition.
     *
     * @param id the varbit id
     * @return the definition, {@link VarBitType#DEFAULT} for an id without one (as in the client), or null if the
     *         archive can't be read
     */
    public VarBitType varbit(int id) {
        synchronized (this) {
            checkArchive();
            if (varbitTable != null) {
                return id >= 0 && id < varbitTable.length ? varbitTable[id] : VarBitType.DEFAULT;
            }
        }
        return load(VARBIT, id, VarBitType::decode, VarBitType.DEFAULT);
    }

    /**
     * An inventory's definition.
     *
     * @param id the inventory id, e.g. 93 for the backpack
     * @return the definition, {@link InvType#DEFAULT} (size 0) for an id without one (as in the client), or null if
     *         the archive can't be read
     */
    public InvType inv(int id) {
        return load(INV, id, InvType::decode, InvType.DEFAULT);
    }

    /**
     * An item's definition as the client's loader builds it (noted, bought and placeholder items merged with the items
     * they stand for). {@code membersWorld} matters because the client rewrites members items on free worlds (see
     * {@link ObjTypeDecoder#load}); the client flushes its own cache when it changes, and this keeps one cache per
     * value.
     *
     * @param id           the item id
     * @param membersWorld whether the client is on a members world
     * @return the definition (the client's default type for an id without a file), or null if the archive can't be
     *         read or the file doesn't decode
     */
    public ObjType obj(int id, boolean membersWorld) {
        return load(OBJ, membersWorld ? 1 : 0, id, files -> ObjTypeDecoder.load(id, files, membersWorld));
    }

    /**
     * An NPC type's definition as the client's loader builds it.
     *
     * @param id the NPC type id (the wiki id, not a loaded NPC's index)
     * @return the definition (the client's default type for an id without a file), or null if the archive can't be
     *         read or the file doesn't decode
     */
    public NpcType npc(int id) {
        return load(NPC, 0, id, files -> NpcTypeDecoder.load(id, files));
    }

    /**
     * A loc's (scenery's) definition as the client's loader builds it. Decoded as the client does at its default
     * detail setting (the low-detail option only drops a second model list, which no Lua function returns).
     *
     * @param id the loc id
     * @return the definition (the client's default type for an id without a file), or null if the archive can't be
     *         read or the file doesn't decode
     */
    public LocType loc(int id) {
        return load(LOC, 0, id, files -> LocTypeDecoder.load(id, files));
    }

    /**
     * Every varbit id grouped by the varp it lives in, like the index the client builds at startup to fire
     * {@code ON_VARBIT_UPDATE}: one entry per varbit id from 0 up to the group's size, ids without a definition
     * landing on varp 0 as they do in the client. Built once (about two reads per varbit, and it keeps every varbit
     * definition for {@link #varbit} from then on).
     *
     * @return varp id to the ids of the varbits in it (ascending), or null if the varbit group can't be read
     */
    public synchronized Map<Integer, int[]> varbitsByVarp() {
        checkArchive();
        if (varbitsByVarp != null) {
            return varbitsByVarp;
        }
        byte[][] files = archive == null ? null : archive.files(VARBIT);
        if (files == null) {
            return null;
        }
        VarBitType[] table = new VarBitType[files.length];
        Map<Integer, List<Integer>> grouped = new HashMap<>();
        for (int id = 0; id < files.length; id++) {
            table[id] = files[id] == null ? VarBitType.DEFAULT : decodeOr(files[id], VarBitType::decode,
                    VarBitType.DEFAULT, VARBIT, id);
            grouped.computeIfAbsent(table[id].baseVar(), k -> new ArrayList<>()).add(id);
        }
        Map<Integer, int[]> index = new HashMap<>(grouped.size() * 2);
        grouped.forEach((varp, ids) -> index.put(varp, ids.stream().mapToInt(Integer::intValue).toArray()));
        varbitTable = table;
        varbitsByVarp = Map.copyOf(index);
        return varbitsByVarp;
    }

    /**
     * A type decoded from one file, cached.
     *
     * @param group  the config group
     * @param id     the type id (file id within the group)
     * @param decode decodes the file's bytes
     * @param absent what the client uses when the group has no file for {@code id}
     * @param <T>    the type's class
     * @return the cached or freshly decoded type, {@code absent} when there's no file (or it doesn't decode), or null
     *         when the archive can't be read
     */
    synchronized <T> T load(int group, int id, Function<byte[], T> decode, T absent) {
        checkArchive();
        long key = key(group, id);
        @SuppressWarnings("unchecked")
        T cached = (T) cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (archive == null || archive.fileCount(group) < 0) {
            return null;
        }
        byte[] data = archive.file(group, id);
        T type = data == null ? absent : decodeOr(data, decode, absent, group, id);
        if (type != null) {
            cache.put(key, type);
        }
        return type;
    }

    /**
     * Like {@link #load(int, int, Function, Object)} but for decoders that read other files of the same group
     * (templates, transforms), and with a variant kept in a separate cache slot.
     *
     * @param group   the config group
     * @param variant tells apart decodes of the same id that depend on client state (e.g. members world); 0 if none
     * @param id      the type id, for the cache key and log messages
     * @param decode  builds the type given the group's file bytes by id (null for an id without a file)
     * @param <T>     the type's class
     * @return the cached or freshly decoded type, or null when the archive can't be read or a file doesn't decode
     */
    synchronized <T> T load(int group, int variant, int id, Function<IntFunction<byte[]>, T> decode) {
        checkArchive();
        long key = key(group | variant << 16, id);
        @SuppressWarnings("unchecked")
        T cached = (T) cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (archive == null || archive.fileCount(group) < 0) {
            return null;
        }
        T type;
        try {
            type = decode.apply(file -> archive.file(group, file));
        } catch (ConfigFormatException e) {
            log.log(Level.FINE, "Config " + group + ":" + id + " didn't decode", e);
            type = null;
        }
        if (type != null) {
            cache.put(key, type);
        }
        return type;
    }

    private <T> T decodeOr(byte[] data, Function<byte[], T> decode, T fallback, int group, int id) {
        try {
            return decode.apply(data);
        } catch (ConfigFormatException e) {
            log.log(Level.FINE, "Config " + group + ":" + id + " didn't decode", e);
            return fallback;
        }
    }

    /**
     * Drops everything cached if the client has rebuilt its archive (a new session of the client, in practice).
     */
    private void checkArchive() {
        long address = archive == null ? 0 : archive.address();
        if (address != cachedArchive) {
            cache.clear();
            varbitsByVarp = null;
            varbitTable = null;
            cachedArchive = address;
        }
    }

    private static long key(int group, int id) {
        return (long) group << 32 | (id & 0xffffffffL);
    }
}
