package com.kraken.client.plugins.npchighlight;

import com.kraken.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a remembered NPC learns its respawn time and tile.
 */
class MemorizedNpcTest {

    private static final int MAX = NpcIndicatorsPlugin.MAX_RESPAWN_TIME_TICKS;
    private static final WorldPoint SPAWN = new WorldPoint(3200, 3200, 0);
    private static final WorldPoint ELSEWHERE = new WorldPoint(3205, 3200, 0);

    @Test
    void aRespawnAfterADeathSetsTheRespawnTimeAndTile() {
        MemorizedNpc npc = new MemorizedNpc(7, "Goblin", 1);
        npc.respawned(100, SPAWN, MAX);
        assertEquals(-1, npc.getRespawnTime(), "a spawn without a death seen teaches no time");
        assertEquals(List.of(SPAWN), npc.getPossibleRespawnLocations());

        npc.setDiedOnTick(110);
        npc.respawned(135, SPAWN, MAX);
        assertEquals(25, npc.getRespawnTime());
        assertEquals(-1, npc.getDiedOnTick(), "alive again");
    }

    @Test
    void onlyShorterTimesWithinTheLongestReplaceTheRespawnTime() {
        MemorizedNpc npc = new MemorizedNpc(7, "Goblin", 1);
        npc.setDiedOnTick(0);
        npc.respawned(30, SPAWN, MAX);
        npc.setDiedOnTick(100);
        npc.respawned(160, SPAWN, MAX);
        assertEquals(30, npc.getRespawnTime(), "a slower respawn came from leaving the area");
        npc.setDiedOnTick(200);
        npc.respawned(220, SPAWN, MAX);
        assertEquals(20, npc.getRespawnTime());

        MemorizedNpc fresh = new MemorizedNpc(8, "Goblin", 1);
        fresh.setDiedOnTick(0);
        fresh.respawned(MAX + 1, SPAWN, MAX);
        assertEquals(-1, fresh.getRespawnTime(), "longer than any respawn");
    }

    @Test
    void theRespawnTileFollowsTheLatestRespawnElsewhere() {
        MemorizedNpc npc = new MemorizedNpc(7, "Goblin", 1);
        npc.respawned(10, SPAWN, MAX);
        npc.respawned(20, SPAWN, MAX);
        assertEquals(List.of(SPAWN), npc.getPossibleRespawnLocations());
        npc.respawned(30, ELSEWHERE, MAX);
        assertEquals(List.of(ELSEWHERE), npc.getPossibleRespawnLocations());
    }

    @Test
    void theRespawnIsOverOnceItsTimeHasPassed() {
        MemorizedNpc npc = new MemorizedNpc(7, "Goblin", 1);
        npc.setRespawnTime(25);
        npc.setDiedOnTick(100);
        assertFalse(npc.respawnedBy(124));
        assertTrue(npc.respawnedBy(125));
    }
}
