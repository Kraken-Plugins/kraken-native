package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link QuickHop#pick}: the next or previous world of the current world's kind, wrapping around, skipping full,
 * offline, out-of-region and too-high total level worlds.
 */
class QuickHopTest {

    private static final Set<WorldRegion> ANY = Set.of();

    private static World world(int id, int location, int players, String activity, WorldType... types) {
        return World.builder()
                .id(id)
                .types(types.length == 0 ? EnumSet.noneOf(WorldType.class) : EnumSet.of(types[0], types))
                .activity(activity)
                .location(location)
                .playerCount(players)
                .build();
    }

    private static Integer pick(List<World> worlds, int current, boolean previous, Set<WorldRegion> regions,
                                boolean outOfDanger, int total, int freeTotal) {
        World world = QuickHop.pick(worlds, current, previous, regions, outOfDanger, total, freeTotal);
        return world == null ? null : world.getId();
    }

    @Test
    void hopsToTheNextWorldOfTheSameKind() {
        List<World> worlds = List.of(
                world(301, 0, 900, "Trade - Free"),
                world(302, 1, 900, "Trade - Members", WorldType.MEMBERS),
                world(303, 7, 900, "-", WorldType.MEMBERS, WorldType.PVP),
                world(304, 0, 900, "-"),
                world(305, 3, 900, "-", WorldType.MEMBERS));
        assertEquals(305, pick(worlds, 302, false, ANY, true, 1000, 500), "skips the free and the PvP worlds");
        assertEquals(302, pick(worlds, 305, false, ANY, true, 1000, 500), "wraps around the end");
        assertEquals(305, pick(worlds, 302, true, ANY, true, 1000, 500), "wraps around the start");
        assertEquals(304, pick(worlds, 301, false, ANY, true, 1000, 500), "free to free");
    }

    @Test
    void skipsFullAndOfflineWorlds() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 0, QuickHop.MAX_PLAYER_COUNT, "-", WorldType.MEMBERS),
                world(304, 0, -1, "-", WorldType.MEMBERS),
                world(305, 0, QuickHop.MAX_PLAYER_COUNT - 1, "-", WorldType.MEMBERS));
        assertEquals(305, pick(worlds, 302, false, ANY, true, 1000, 500));
    }

    @Test
    void staysInTheChosenRegions() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 1, 900, "-", WorldType.MEMBERS),
                world(304, 7, 900, "-", WorldType.MEMBERS),
                world(305, 99, 900, "-", WorldType.MEMBERS));
        assertEquals(304, pick(worlds, 302, false, Set.of(WorldRegion.GERMANY), true, 1000, 500));
        assertEquals(303, pick(worlds, 302, false, Set.of(WorldRegion.UNITED_KINGDOM), true, 1000, 500));
        assertEquals(305, pick(worlds, 304, false, Set.of(WorldRegion.GERMANY), true, 1000, 500),
                "a location no region has isn't filtered out");
    }

    @Test
    void takesTotalLevelWorldsThePlayerCanJoin() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 0, 900, "2200 skill total", WorldType.MEMBERS, WorldType.SKILL_TOTAL),
                world(304, 0, 900, "1500 skill total", WorldType.MEMBERS, WorldType.SKILL_TOTAL),
                world(305, 0, 900, "-"),
                world(306, 0, 900, "750 skill total", WorldType.SKILL_TOTAL));
        assertEquals(304, pick(worlds, 302, false, ANY, true, 1600, 700), "2200 is too high, 1500 isn't");
        assertEquals(302, pick(worlds, 304, false, ANY, true, 1600, 700), "from a total world back to a plain one");
        assertNull(pick(worlds, 305, false, ANY, true, 1600, 700),
                "a free total world counts only the free skills: 700 is below 750");
        assertEquals(306, pick(worlds, 305, false, ANY, true, 1600, 800));
    }

    @Test
    void leavesDangerousWorldsUnlessAskedNotTo() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 0, 900, "-", WorldType.MEMBERS, WorldType.PVP),
                world(304, 0, 900, "-", WorldType.MEMBERS, WorldType.PVP));
        assertEquals(302, pick(worlds, 303, false, ANY, true, 1000, 500), "out of danger");
        assertEquals(304, pick(worlds, 303, false, ANY, false, 1000, 500), "PvP to PvP");
    }

    @Test
    void bountyHunterAndLastManStandingDontCount() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 0, 900, "Bounty Hunter", WorldType.MEMBERS, WorldType.BOUNTY),
                world(304, 0, 900, "LMS Casual", WorldType.MEMBERS, WorldType.LAST_MAN_STANDING));
        assertEquals(303, pick(worlds, 302, false, ANY, true, 1000, 500));
        assertEquals(304, pick(worlds, 303, false, ANY, true, 1000, 500));
    }

    @Test
    void noWorldWhenTheCurrentOneIsUnlistedOrAloneInItsKind() {
        List<World> worlds = List.of(
                world(302, 0, 900, "-", WorldType.MEMBERS),
                world(303, 0, 900, "-"));
        assertNull(pick(worlds, 999, false, ANY, true, 1000, 500), "unlisted");
        assertNull(pick(worlds, 302, false, ANY, true, 1000, 500), "the only members world");
        assertNull(pick(List.of(), 302, false, ANY, true, 1000, 500), "no list");
    }

    @Test
    void readsTheRequiredTotalFromTheActivity() {
        assertEquals(2000, QuickHop.requiredTotal("2000 skill total"));
        assertEquals(500, QuickHop.requiredTotal("500 skill total"));
        assertEquals(-1, QuickHop.requiredTotal("Skill total"));
        assertEquals(-1, QuickHop.requiredTotal(""));
    }
}
