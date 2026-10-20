package com.kraken.api;

import lombok.Getter;

import java.util.Collection;
import java.util.EnumSet;

/**
 * A kind of world, as RuneLite's {@code WorldType}: each is one bit of the client's world flags
 * ({@code Client.worldFlags}), the same bits in the native client.
 */
public enum WorldType {

    /**
     * A members world.
     */
    MEMBERS(1),

    /**
     * A PvP world.
     */
    PVP(1 << 2),

    /**
     * A Bounty Hunter world.
     */
    BOUNTY(1 << 5),

    /**
     * A PvP Arena world.
     */
    PVP_ARENA(1 << 6),

    /**
     * A world with a total level requirement.
     */
    SKILL_TOTAL(1 << 7),

    /**
     * A quest speedrunning world.
     */
    QUEST_SPEEDRUNNING(1 << 8),

    /**
     * A high-risk world.
     */
    HIGH_RISK(1 << 10),

    /**
     * A Last Man Standing world.
     */
    LAST_MAN_STANDING(1 << 14),

    /**
     * A beta world.
     */
    BETA_WORLD(1 << 16),

    /**
     * A beta world whose progress isn't saved.
     */
    NOSAVE_MODE(1 << 25),

    /**
     * A tournament world.
     */
    TOURNAMENT_WORLD(1 << 26),

    /**
     * A Fresh Start world.
     */
    FRESH_START_WORLD(1 << 27),

    /**
     * A Deadman world.
     */
    DEADMAN(1 << 29),

    /**
     * A seasonal world (Leagues, seasonal Deadman).
     */
    SEASONAL(1 << 30);

    private static final EnumSet<WorldType> PVP_WORLD_TYPES = EnumSet.of(DEADMAN, PVP);

    /**
     * This type's bit in the world flags.
     */
    @Getter
    private final int mask;

    WorldType(int mask) {
        this.mask = mask;
    }

    /**
     * The world types a set of world flags has.
     *
     * @param mask the client's world flags
     * @return every type whose bit is set; bits no type uses are ignored
     */
    public static EnumSet<WorldType> fromMask(int mask) {
        EnumSet<WorldType> types = EnumSet.noneOf(WorldType.class);
        for (WorldType type : values()) {
            if ((mask & type.mask) != 0) {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * The world flags for a set of world types.
     *
     * @param types the world types
     * @return their bits, or-ed together
     */
    public static int toMask(EnumSet<WorldType> types) {
        int mask = 0;
        for (WorldType type : types) {
            mask |= type.mask;
        }
        return mask;
    }

    /**
     * Whether players can attack each other anywhere on a world with these types, as RuneLite decides it.
     *
     * @param types the world's types
     * @return true for a PvP or Deadman world
     */
    public static boolean isPvpWorld(Collection<WorldType> types) {
        for (WorldType type : types) {
            if (PVP_WORLD_TYPES.contains(type)) {
                return true;
            }
        }
        return false;
    }
}
