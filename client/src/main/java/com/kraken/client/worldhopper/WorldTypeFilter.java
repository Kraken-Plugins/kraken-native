package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The kinds of world the world list can be narrowed to.
 */
public enum WorldTypeFilter {
    ALL("All types", world -> true),
    FREE("Free", world -> !world.hasType(WorldType.MEMBERS)),
    MEMBERS("Members", world -> world.hasType(WorldType.MEMBERS)),
    NORMAL("Normal", WorldTypeFilter::isNormal),
    SKILL_TOTAL("Skill total", world -> world.hasType(WorldType.SKILL_TOTAL)),
    PVP("PvP", world -> world.hasType(WorldType.PVP)),
    HIGH_RISK("High risk", world -> world.hasType(WorldType.HIGH_RISK) && !world.hasType(WorldType.PVP)),
    DEADMAN("Deadman", world -> world.hasType(WorldType.DEADMAN)),
    SEASONAL("Seasonal", world -> world.hasType(WorldType.SEASONAL)
            || world.hasType(WorldType.TOURNAMENT_WORLD)),
    QUEST_SPEEDRUNNING("Quest speedrunning", world -> world.hasType(WorldType.QUEST_SPEEDRUNNING)),
    FRESH_START("Fresh Start", world -> world.hasType(WorldType.FRESH_START_WORLD)),
    PVP_ARENA("PvP Arena", world -> world.hasType(WorldType.PVP_ARENA)),
    LAST_MAN_STANDING("Last Man Standing", world -> world.hasType(WorldType.LAST_MAN_STANDING)),
    BOUNTY_HUNTER("Bounty Hunter", world -> world.hasType(WorldType.BOUNTY)),
    BETA("Beta", world -> world.hasType(WorldType.BETA_WORLD) || world.hasType(WorldType.NOSAVE_MODE));

    /**
     * The types a normal world may have: being members, a total level requirement, or Last Man Standing.
     */
    private static final Set<WorldType> NORMAL_TYPES = EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL,
            WorldType.LAST_MAN_STANDING);

    private final String label;
    private final Predicate<World> test;

    WorldTypeFilter(String label, Predicate<World> test) {
        this.label = label;
        this.test = test;
    }

    /**
     * Whether a world is of this kind.
     *
     * @param world the world
     * @return true if the filter keeps it
     */
    public boolean matches(World world) {
        return test.test(world);
    }

    @Override
    public String toString() {
        return label;
    }

    private static boolean isNormal(World world) {
        EnumSet<WorldType> types = world.getTypes();
        types.removeAll(NORMAL_TYPES);
        return types.isEmpty();
    }
}
