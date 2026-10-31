package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the world a quick-hop goes to: the next (or previous) world by number, wrapping around the list, that is
 * the same kind of world as the current one and that the player can join.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class QuickHop {

    /**
     * Worlds this full are skipped: the server refuses a hop to a full world.
     */
    static final int MAX_PLAYER_COUNT = 1950;

    /**
     * The world a quick-hop goes to. A candidate must be online and below {@link #MAX_PLAYER_COUNT} players, in one of
     * the regions if any are given, and have the current world's types, where Bounty Hunter, Last Man Standing and a
     * total level requirement the player meets don't count (and, with {@code outOfDanger}, the current world's PvP and
     * high risk don't count either, so a quick-hop always leaves them). A free world's total level requirement counts
     * only the free skills.
     *
     * @param worlds         the world list, sorted by number
     * @param currentId      the number of the world the player is on
     * @param previous       true to go down the list, false to go up
     * @param regions        the regions to stay in; empty for any
     * @param outOfDanger    true to hop out of PvP and high-risk worlds to safe ones
     * @param totalLevel     the player's total level
     * @param freeTotalLevel the player's total level over the free skills only
     * @return the world to hop to, or null if the current world isn't in the list or no other world qualifies
     */
    static World pick(List<World> worlds, int currentId, boolean previous, Set<WorldRegion> regions,
                      boolean outOfDanger, int totalLevel, int freeTotalLevel) {
        int index = -1;
        for (int i = 0; i < worlds.size(); i++) {
            if (worlds.get(i).getId() == currentId) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        EnumSet<WorldType> wanted = worlds.get(index).getTypes();
        if (outOfDanger) {
            wanted.remove(WorldType.PVP);
            wanted.remove(WorldType.HIGH_RISK);
        }
        wanted.remove(WorldType.BOUNTY);
        wanted.remove(WorldType.SKILL_TOTAL);
        wanted.remove(WorldType.LAST_MAN_STANDING);

        int size = worlds.size();
        for (int step = 1; step < size; step++) {
            World world = worlds.get(Math.floorMod(index + (previous ? -step : step), size));
            WorldRegion region = WorldRegion.of(world.getLocation());
            if (!regions.isEmpty() && region != null && !regions.contains(region)) {
                continue;
            }
            if (world.getPlayerCount() < 0 || world.getPlayerCount() >= MAX_PLAYER_COUNT) {
                continue;
            }
            EnumSet<WorldType> types = world.getTypes();
            types.remove(WorldType.BOUNTY);
            types.remove(WorldType.LAST_MAN_STANDING);
            if (types.contains(WorldType.SKILL_TOTAL)) {
                int required = requiredTotal(world.getActivity());
                int level = types.contains(WorldType.MEMBERS) ? totalLevel : freeTotalLevel;
                if (required >= 0 && level >= required) {
                    types.remove(WorldType.SKILL_TOTAL);
                }
            }
            if (types.equals(wanted)) {
                return world;
            }
        }
        return null;
    }

    /**
     * The total level a skill total world asks for, from its activity ("2000 skill total").
     *
     * @param activity the world's activity
     * @return the level, or -1 if the activity doesn't start with one
     */
    static int requiredTotal(String activity) {
        int end = 0;
        while (end < activity.length() && Character.isDigit(activity.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 6) {
            return -1;
        }
        return Integer.parseInt(activity.substring(0, end));
    }
}
