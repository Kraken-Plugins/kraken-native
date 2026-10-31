package com.kraken.client.worldhopper;

import com.kraken.api.World;
import com.kraken.api.WorldType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world list's filters: {@link WorldTypeFilter}, the search ({@link WorldHopperPanel#matches}) and the activity
 * colours.
 */
class WorldFiltersTest {

    private static World world(int id, String activity, int location, WorldType... types) {
        return World.builder()
                .id(id)
                .types(types.length == 0 ? EnumSet.noneOf(WorldType.class) : EnumSet.of(types[0], types))
                .activity(activity)
                .location(location)
                .build();
    }

    @Test
    void typeFiltersKeepTheirKindOfWorld() {
        World free = world(301, "Trade - Free", 0);
        World members = world(302, "Trade - Members", 0, WorldType.MEMBERS);
        World total = world(353, "1250 skill total", 0, WorldType.MEMBERS, WorldType.SKILL_TOTAL);
        World pvp = world(325, "PvP World", 0, WorldType.MEMBERS, WorldType.PVP);
        World pvpHighRisk = world(579, "PvP World - High Risk", 0, WorldType.MEMBERS, WorldType.PVP,
                WorldType.HIGH_RISK);
        World highRisk = world(365, "High Risk World", 1, WorldType.MEMBERS, WorldType.HIGH_RISK);
        World leagues = world(400, "Leagues", 0, WorldType.MEMBERS, WorldType.SEASONAL);

        for (World each : new World[]{free, members, total, pvp, highRisk, leagues}) {
            assertTrue(WorldTypeFilter.ALL.matches(each));
        }
        assertTrue(WorldTypeFilter.FREE.matches(free));
        assertFalse(WorldTypeFilter.FREE.matches(members));
        assertTrue(WorldTypeFilter.MEMBERS.matches(total));
        assertTrue(WorldTypeFilter.NORMAL.matches(free));
        assertTrue(WorldTypeFilter.NORMAL.matches(total), "a total level requirement is normal");
        assertFalse(WorldTypeFilter.NORMAL.matches(pvp));
        assertFalse(WorldTypeFilter.NORMAL.matches(leagues));
        assertTrue(WorldTypeFilter.HIGH_RISK.matches(highRisk));
        assertFalse(WorldTypeFilter.HIGH_RISK.matches(pvpHighRisk), "a PvP world counts as PvP only");
        assertTrue(WorldTypeFilter.PVP.matches(pvpHighRisk));
        assertTrue(WorldTypeFilter.SEASONAL.matches(leagues));
        assertTrue(WorldTypeFilter.SKILL_TOTAL.matches(total));
    }

    @Test
    void theSearchMatchesNumbersActivitiesAndRegions() {
        World world = world(330, "Blast Furnace", 7, WorldType.MEMBERS);
        WorldRegion region = WorldRegion.of(world.getLocation());
        assertTrue(WorldHopperPanel.matches(world, region, "33"), "number prefix");
        assertFalse(WorldHopperPanel.matches(world, region, "30"), "not a prefix");
        assertTrue(WorldHopperPanel.matches(world, region, "furnace"));
        assertTrue(WorldHopperPanel.matches(world, region, "germ"));
        assertTrue(WorldHopperPanel.matches(world, region, "de"), "region code");
        assertFalse(WorldHopperPanel.matches(world, region, "wintertodt"));
        assertFalse(WorldHopperPanel.matches(world, null, "germ"), "no region known");
    }

    @Test
    void activitiesAreColouredByKind() {
        World pvp = world(325, "PvP World", 0, WorldType.MEMBERS, WorldType.PVP);
        World deadman = world(345, "Deadman", 0, WorldType.DEADMAN);
        World plain = world(302, "-", 0, WorldType.MEMBERS);
        assertEquals(WorldHopperPanel.activityColor(pvp), WorldHopperPanel.activityColor(deadman),
                "both dangerous");
        assertFalse(WorldHopperPanel.activityColor(pvp).equals(WorldHopperPanel.activityColor(plain)));
    }
}
