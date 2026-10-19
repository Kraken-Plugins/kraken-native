package com.kraken.api.game;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors the client's Lua {@code osrs.ClientState} namespace: stats, varps, varcs, the tick counter, login state and
 * profile type. Each method follows what the native Lua handler reads, so it returns what the Lua function would
 * (including its fallbacks, such as -1 for stats before the server has sent them).
 *
 * <p>Not yet available: {@code getVarbit}. A varbit is a slice of a varp described by a VarBitType definition, and the
 * client only keeps recently used definitions in memory, so it needs the config-type reader that also unlocks
 * {@code ocName}, {@code invSize} and friends.
 */
public final class ClientState extends GameReader {

    /** Mirrors {@code ClientState.Stats}: each stat's id, as the stat functions take it. */
    public enum Stats {
        SKILL_ATTACK(0, "Attack"), SKILL_DEFENCE(1, "Defence"), SKILL_STRENGTH(2, "Strength"),
        SKILL_HITPOINTS(3, "Hitpoints"), SKILL_RANGED(4, "Ranged"), SKILL_PRAYER(5, "Prayer"), SKILL_MAGIC(6, "Magic"),
        SKILL_COOKING(7, "Cooking"), SKILL_WOODCUTTING(8, "Woodcutting"), SKILL_FLETCHING(9, "Fletching"),
        SKILL_FISHING(10, "Fishing"), SKILL_FIREMAKING(11, "Firemaking"), SKILL_CRAFTING(12, "Crafting"),
        SKILL_SMITHING(13, "Smithing"), SKILL_MINING(14, "Mining"), SKILL_HERBLORE(15, "Herblore"),
        SKILL_AGILITY(16, "Agility"), SKILL_THIEVING(17, "Thieving"), SKILL_SLAYER(18, "Slayer"),
        SKILL_FARMING(19, "Farming"), SKILL_RUNECRAFTING(20, "Runecrafting"), SKILL_HUNTER(21, "Hunter"),
        SKILL_CONSTRUCTION(22, "Construction"), SKILL_SAILING(23, "Sailing");

        private final int id;
        private final String displayName;

        Stats(int id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public int id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The stat with this id, or null. */
        public static Stats of(int id) {
            for (Stats stat : values()) {
                if (stat.id == id) {
                    return stat;
                }
            }
            return null;
        }
    }

    /** Mirrors {@code ClientState.ProfileType}: the kind of world the account is logged in to. */
    public enum ProfileType {
        STANDARD(0), BETA(1), DEADMAN(2), PVP_ARENA(3), QUEST_SPEEDRUNNING(4), SEASONAL(5);

        private final int value;

        ProfileType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static ProfileType of(int value) {
            for (ProfileType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return STANDARD;
        }
    }

    // Raw getGameState() values, as the client's setGameState switches on them (same numbers as the Java client's).
    // Only LOGGED_IN is in the mapping (it's what isLoggedIn compares with); the rest are long-stable protocol values.
    /** The title (login) screen. */
    public static final int GAME_STATE_TITLE = 10;
    /** Logging in, between the title screen and the world. */
    public static final int GAME_STATE_LOGGING_IN = 20;
    /** Loading the map. */
    public static final int GAME_STATE_LOADING = 25;
    /** In the world. */
    public static final int GAME_STATE_LOGGED_IN = 30;
    /** Lost the connection; the client tries to reconnect. */
    public static final int GAME_STATE_CONNECTION_LOST = 40;
    /** Hopping to another world. */
    public static final int GAME_STATE_HOPPING = 45;

    /** Highest level the remaining-XP calculation goes to, as in the client. */
    private static final int MAX_LEVEL = 99;
    /** XP_FOR_LEVEL[n] = total XP needed for level n (n = 1..99); the client keeps the same table from level 2 up. */
    private static final int[] XP_FOR_LEVEL = xpTable();

    private static final int MAX_VARPS = 1 << 16;
    private static final int MAX_VARCS = 1 << 16;
    /** Variant tags of a varc entry's value (eastl::variant index + 1, as the client compares it). */
    private static final int VARC_INT = 1;
    private static final int VARC_STRING = 2;

    private final int cGameState;
    private final int cLoginToken;
    private final int cTick;
    private final int gameStateLoggedIn;

    private final int cSkillLevel;
    private final int cSkillBase;
    private final int cSkillXp;
    private final int cStatsValid;
    private final int statCount;

    private final long varpsRva;
    private final long varpCountRva;

    private final int cWorldFlags;
    private final int cVarcs;
    private final int varcsBegin;
    private final int varcsEnd;
    private final int varcStride;
    private final int varcKey;
    private final int varcValue;
    private final int varcType;

    public ClientState(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.cGameState = mapping.offset("Client.gameState");
        this.cLoginToken = mapping.offset("Client.loginToken");
        this.cTick = mapping.offset("Client.tickCount");
        this.gameStateLoggedIn = mapping.enumValue("gameState", "LOGGED_IN", 30);

        this.cSkillLevel = mapping.offset("Client.skillLevel");
        this.cSkillBase = mapping.offset("Client.skillBase");
        this.cSkillXp = mapping.offset("Client.skillXp");
        this.cStatsValid = mapping.offset("Client.statsValidGate");
        // The stat getters accept ids up to skillIndexMax inclusive, but each array holds skillIndexMax entries (the
        // next array starts right after), so the last id the client accepts reads a neighbouring array. Stop short.
        this.statCount = mapping.offset("Client.skillIndexMax");

        this.varpsRva = mapping.global("varps");
        this.varpCountRva = mapping.hasGlobal("varpCount") ? mapping.global("varpCount") : -1;

        // Added to the mapping after the seed build's first cut; absent keys disable just these reads.
        this.cWorldFlags = optionalOffset("Client.worldFlags");
        this.cVarcs = optionalOffset("Client.varcs");
        this.varcsBegin = optionalOffset("Varcs.entries");
        this.varcsEnd = optionalOffset("Varcs.entriesEnd");
        this.varcStride = optionalOffset("VarcEntry.size");
        this.varcKey = optionalOffset("VarcEntry.key");
        this.varcValue = optionalOffset("VarcEntry.value");
        this.varcType = optionalOffset("VarcEntry.type");
    }

    // ---- Login and ticks -----------------------------------------------------------------------

    /**
     * The client's raw game state ({@code Client.gameState}), or -1 if unreadable. Not a Lua function. Values seen in
     * the client: 10 title screen, 20 logging in, 25 loading, 30 logged in, 40 connection lost, 45 hopping worlds.
     */
    public int getGameState() {
        long client = clientBase();
        return client == 0 ? -1 : safeInt(client + cGameState, -1);
    }

    /** Mirrors {@code ClientState.isLoggedIn}: the client has a login session and is in the logged-in game state. */
    public boolean isLoggedIn() {
        long client = clientBase();
        if (client == 0) {
            return false;
        }
        return safeLong(client + cLoginToken, -1) != -1 && safeInt(client + cGameState, -1) == gameStateLoggedIn;
    }

    /** Mirrors {@code ClientState.getTickCount}: server ticks processed since login. */
    public int getTickCount() {
        long client = clientBase();
        return client == 0 ? 0 : safeInt(client + cTick, 0);
    }

    /**
     * Mirrors {@code ClientState.getProfileType}: decodes the world-type flags the same way the native handler does
     * (beta, then deadman, PvP arena, quest speedrunning and seasonal, the later ones winning). STANDARD if the flags
     * can't be read.
     */
    public ProfileType getProfileType() {
        int flags = getWorldFlags();
        int type = flags >>> 16 & 1;               // BETA
        if ((flags & 1 << 29) != 0) {
            type = 2;                             // DEADMAN
        }
        if ((flags & 1 << 6) != 0) {
            type = 3;                             // PVP_ARENA
        }
        if ((flags & 1 << 8) != 0) {
            type = 4;                             // QUEST_SPEEDRUNNING
        }
        if ((flags & 1 << 30) != 0) {
            type = 5;                             // SEASONAL
        }
        return ProfileType.of(type);
    }

    /** The raw world-type bitmask {@link #getProfileType} decodes, or 0. Not a Lua function. */
    public int getWorldFlags() {
        long client = clientBase();
        return client == 0 || cWorldFlags < 0 ? 0 : safeInt(client + cWorldFlags, 0);
    }

    // ---- Stats ------------------------------------------------------------------------------------

    /** Mirrors {@code ClientState.getStatEffectiveLevel}: current (boosted or drained) level, or -1. */
    public int getStatEffectiveLevel(int statId) {
        return statValue(cSkillLevel, statId);
    }

    public int getStatEffectiveLevel(Stats stat) {
        return getStatEffectiveLevel(stat.id());
    }

    /** Mirrors {@code ClientState.getStatBaseLevel}: the level from XP, or -1 before the server sends stats. */
    public int getStatBaseLevel(int statId) {
        return statValue(cSkillBase, statId);
    }

    public int getStatBaseLevel(Stats stat) {
        return getStatBaseLevel(stat.id());
    }

    /** Mirrors {@code ClientState.getStatXP}: the stat's XP, or -1 before the server sends stats. */
    public int getStatXP(int statId) {
        return statValue(cSkillXp, statId);
    }

    public int getStatXP(Stats stat) {
        return getStatXP(stat.id());
    }

    /** Mirrors {@code ClientState.getStatRemainingXPUntilLevel(statId)}: XP left until the next level. */
    public int getStatRemainingXPUntilLevel(int statId) {
        return remainingXp(statId, null);
    }

    /**
     * Mirrors {@code ClientState.getStatRemainingXPUntilLevel(statId, level)}: XP left until {@code level}. Like the
     * client: -1 before the server sends stats, 0 if {@code level} is below the current base level or above 99.
     */
    public int getStatRemainingXPUntilLevel(int statId, int level) {
        return remainingXp(statId, level);
    }

    public int getStatRemainingXPUntilLevel(Stats stat) {
        return getStatRemainingXPUntilLevel(stat.id());
    }

    /** Whether the server has sent the stats yet (the gate the stat functions check before returning -1). */
    public boolean areStatsTransmitted() {
        long client = clientBase();
        return client != 0 && safeInt(client + cStatsValid, 0) > 0;
    }

    /** Number of stat slots the client keeps (ids 0 to this minus 1). Not a Lua function. */
    public int getStatCount() {
        return statCount;
    }

    /**
     * Every stat slot's {xp, effective level, base level} in three reads, or null before the server sends stats.
     * Not a Lua function; the {@code ON_STAT_TRANSMIT} event and the dev tools use it to avoid one read per stat.
     */
    public StatArrays getStatsAll() {
        long client = clientBase();
        if (client == 0 || safeInt(client + cStatsValid, 0) <= 0) {
            return null;
        }
        int[] xp = readInts(client + cSkillXp, statCount);
        int[] level = readInts(client + cSkillLevel, statCount);
        int[] base = readInts(client + cSkillBase, statCount);
        return xp == null || level == null || base == null ? null : new StatArrays(xp, level, base);
    }

    /**
     * Every stat at once, indexed by stat id. Arrays are owned by the caller.
     *
     * @param xp    XP per stat
     * @param level effective (current) level per stat
     * @param base  base level per stat
     */
    public record StatArrays(int[] xp, int[] level, int[] base) {
    }

    // ---- Vars ---------------------------------------------------------------------------------------

    /**
     * Mirrors {@code ClientState.getVarp}: the value of a varp as the server last sent it. The native handler doesn't
     * bounds-check; this returns -1 for an id outside the client's varp array instead of reading past it.
     */
    public int getVarp(int id) {
        if (id < 0 || id >= getVarpCount()) {
            return -1;
        }
        long varps = safePointer(memory.moduleBase() + varpsRva);
        return varps == 0 ? -1 : safeInt(varps + id * 4L, -1);
    }

    /** How many varps the client has (ids 0 to this minus 1), or 0 if unknown. Not a Lua function. */
    public int getVarpCount() {
        if (varpCountRva < 0) {
            return 0;
        }
        int count = safeInt(memory.moduleBase() + varpCountRva, 0);
        return count > 0 && count <= MAX_VARPS ? count : 0;
    }

    /** Every varp in one read (index = varp id), or an empty array if unavailable. Not a Lua function. */
    public int[] getVarps() {
        int count = getVarpCount();
        long varps = safePointer(memory.moduleBase() + varpsRva);
        int[] values = count == 0 || varps == 0 ? null : readInts(varps, count);
        return values == null ? new int[0] : values;
    }

    /** Mirrors {@code ClientState.getIntegerVarc}: an integer varc's value, or -1 if it isn't set (or isn't an int). */
    public int getIntegerVarc(int id) {
        long entry = findVarc(id);
        if (entry == 0 || safeU8(entry + varcType, 0) != VARC_INT) {
            return -1;
        }
        return safeInt(entry + varcValue, -1);
    }

    /** Mirrors {@code ClientState.getStringVarc}: a string varc's value, or "" if it isn't set (or isn't a string). */
    public String getStringVarc(int id) {
        long entry = findVarc(id);
        if (entry == 0 || safeU8(entry + varcType, 0) != VARC_STRING) {
            return "";
        }
        return structs.string(entry + varcValue);
    }

    /** Every integer varc the client holds, by id. Not a Lua function; the var inspector uses it. */
    public Map<Integer, Integer> getIntegerVarcs() {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        forEachVarc((id, type, entry, block, at) -> {
            if (type == VARC_INT) {
                out.put(id, block.getInt(at + varcValue));
            }
        });
        return out;
    }

    /** Every string varc the client holds, by id. Not a Lua function; the var inspector uses it. */
    public Map<Integer, String> getStringVarcs() {
        Map<Integer, String> out = new LinkedHashMap<>();
        forEachVarc((id, type, entry, block, at) -> {
            if (type == VARC_STRING) {
                out.put(id, structs.string(entry + varcValue));
            }
        });
        return out;
    }

    /** Whether this build's mapping has what the varc functions need. */
    public boolean hasVarcs() {
        return cVarcs >= 0 && varcsBegin >= 0 && varcsEnd >= 0 && varcStride > 0 && varcKey >= 0 && varcValue >= 0
                && varcType >= 0;
    }

    // ---- internals -------------------------------------------------------------------------------------

    private int statValue(int arrayOffset, int statId) {
        long client = clientBase();
        if (client == 0 || statId < 0 || statId >= statCount || safeInt(client + cStatsValid, 0) <= 0) {
            return -1;
        }
        return safeInt(client + arrayOffset + statId * 4L, -1);
    }

    private int remainingXp(int statId, Integer level) {
        int base = getStatBaseLevel(statId);
        int xp = getStatXP(statId);
        if (base < 0 || xp < 0) {
            return -1;
        }
        int target = level != null ? level : base + 1;
        if (target < base || target > MAX_LEVEL) {
            return 0;
        }
        // Same subtraction as the client, so it can be negative when target == base.
        return XP_FOR_LEVEL[Math.max(target, 1)] - xp;
    }

    private static int[] xpTable() {
        int[] table = new int[MAX_LEVEL + 1];
        int points = 0;
        for (int level = 1; level < MAX_LEVEL; level++) {
            points += (int) Math.floor(level + 300 * Math.pow(2, level / 7.0));
            table[level + 1] = points / 4;
        }
        return table;
    }

    /** The varc store's sorted entry array as {begin, count}, or null if unavailable. */
    private long[] varcEntries() {
        if (!hasVarcs()) {
            return null;
        }
        long client = clientBase();
        long store = client == 0 ? 0 : safePointer(client + cVarcs);
        if (store == 0) {
            return null;
        }
        long begin = safePointer(store + varcsBegin);
        long end = safePointer(store + varcsEnd);
        long count = (end - begin) / varcStride;
        if (begin == 0 || count <= 0 || count > MAX_VARCS || (end - begin) % varcStride != 0) {
            return null;
        }
        return new long[]{begin, count};
    }

    /** Binary search over the sorted entries, as the client's own lookup does; the entry's address or 0. */
    private long findVarc(int id) {
        long[] entries = varcEntries();
        if (entries == null) {
            return 0;
        }
        long lo = 0;
        long hi = entries[1];
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            int key = safeInt(entries[0] + mid * varcStride + varcKey, Integer.MAX_VALUE);
            if (key < id) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo >= entries[1]) {
            return 0;
        }
        long entry = entries[0] + lo * varcStride;
        return safeInt(entry + varcKey, Integer.MIN_VALUE) == id ? entry : 0;
    }

    private interface VarcVisitor {
        void visit(int id, int type, long entry, ByteBuffer block, int at);
    }

    private void forEachVarc(VarcVisitor visitor) {
        long[] entries = varcEntries();
        if (entries == null) {
            return;
        }
        ByteBuffer block = readBlock(entries[0], (int) (entries[1] * varcStride));
        if (block == null) {
            return;
        }
        for (int i = 0; i < entries[1]; i++) {
            int at = i * varcStride;
            visitor.visit(block.getInt(at + varcKey), block.get(at + varcType) & 0xff, entries[0] + at, block, at);
        }
    }
}
