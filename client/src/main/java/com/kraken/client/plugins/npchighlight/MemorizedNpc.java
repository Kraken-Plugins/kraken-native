package com.kraken.client.plugins.npchighlight;

import com.kraken.api.actor.NPC;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.NPCComposition;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A highlighted NPC remembered by its index, to learn how long it takes to respawn and where: the tick it died on, the
 * ticks between a death and the next spawn, and the tiles it has respawned on.
 */
@Getter
class MemorizedNpc {

    /**
     * The NPC's index, which a respawned NPC takes again.
     */
    private final int npcIndex;

    /**
     * The NPC's name.
     */
    private final String npcName;

    /**
     * The NPC's size in tiles.
     */
    private final int npcSize;

    /**
     * The tick the NPC died on, by the client's tick count; -1 while it is alive.
     */
    @Setter
    private int diedOnTick = -1;

    /**
     * The ticks it takes the NPC to respawn; -1 until a respawn has been seen.
     */
    @Setter
    private int respawnTime = -1;

    /**
     * The tiles the NPC may respawn on: those it has respawned on so far.
     */
    private final List<WorldPoint> possibleRespawnLocations = new ArrayList<>(2);

    /**
     * Remembers an NPC as it is now.
     *
     * @param npc the NPC
     */
    MemorizedNpc(NPC npc) {
        this(npc.getIndex(), npc.getName(), sizeOf(npc));
    }

    /**
     * Remembers an NPC by its index, name and size.
     *
     * @param npcIndex the index
     * @param npcName  the name
     * @param npcSize  the size in tiles
     */
    MemorizedNpc(int npcIndex, String npcName, int npcSize) {
        this.npcIndex = npcIndex;
        this.npcName = npcName;
        this.npcSize = npcSize;
    }

    /**
     * Notes that the NPC respawned on a tile. If it had died, the ticks since are its respawn time, unless a shorter
     * one was seen before (leaving the area after a kill makes a respawn look slower) or they are more than the longest
     * respawn time; then the tiles it may respawn on narrow to this one.
     *
     * @param tick     the tick it respawned on
     * @param location where it respawned
     * @param maxTicks the longest respawn time believed
     */
    void respawned(int tick, WorldPoint location, int maxTicks) {
        if (diedOnTick != -1) {
            int observed = tick - diedOnTick;
            if ((respawnTime == -1 || observed < respawnTime) && observed <= maxTicks) {
                respawnTime = observed;
            }
            diedOnTick = -1;
        }
        possibleRespawnLocations.removeIf(tile -> !tile.equals(location));
        if (possibleRespawnLocations.isEmpty()) {
            possibleRespawnLocations.add(location);
        }
    }

    /**
     * Whether the NPC's respawn is over by a tick: it died, and its respawn time has passed.
     *
     * @param tick the tick
     * @return true once the NPC should have respawned
     */
    boolean respawnedBy(int tick) {
        return diedOnTick + respawnTime <= tick;
    }

    private static int sizeOf(NPC npc) {
        NPCComposition composition = npc.getTransformedComposition();
        return composition == null ? 1 : composition.getSize();
    }
}
