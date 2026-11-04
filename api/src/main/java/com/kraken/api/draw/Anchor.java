package com.kraken.api.draw;

import com.kraken.api.actor.NPC;

import java.util.Objects;

/**
 * Where a {@link DrawItem} sits in the scene. The client's native hook finds the anchor anew in every frame, from the
 * state that frame was drawn with, so an item moves with its NPC and the camera in the same frame. An anchor names an
 * NPC by its index and the cycle it was created on: once that NPC is gone, its items draw nothing.
 *
 * <p>The point can be shifted along the ground ({@link #offset}) and raised above it ({@link #lift}), both in local
 * units (128 to a tile).
 *
 * @param kind       which point of the NPC
 * @param index      the NPC's index
 * @param spawnCycle the cycle the NPC was created on
 * @param dx         the shift east, in local units
 * @param dy         the shift north, in local units
 * @param lift       how far above the ground, in local units
 */
public record Anchor(Kind kind, int index, int spawnCycle, int dx, int dy, int lift) {

    /**
     * Which point of an NPC an anchor follows.
     */
    public enum Kind {
        /**
         * Where the NPC is drawn: the centre of its footprint, moving smoothly between tiles.
         */
        NPC,
        /**
         * The centre of the tile the server put the NPC on (its south-west tile for a large NPC), which moves a tile
         * at a time.
         */
        NPC_TILE
    }

    /**
     * Checks the kind.
     *
     * @param kind       which point of the NPC
     * @param index      the NPC's index
     * @param spawnCycle the cycle the NPC was created on
     * @param dx         the shift east, in local units
     * @param dy         the shift north, in local units
     * @param lift       how far above the ground, in local units
     */
    public Anchor {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Follows where an NPC is drawn: the centre of its footprint, on the ground.
     *
     * @param npc the NPC
     * @return the anchor
     */
    public static Anchor npc(NPC npc) {
        return new Anchor(Kind.NPC, npc.getIndex(), npc.getSpawnCycle(), 0, 0, 0);
    }

    /**
     * Follows the tile the server put an NPC on: the centre of that tile (the south-west tile of a large NPC), on the
     * ground.
     *
     * @param npc the NPC
     * @return the anchor
     */
    public static Anchor npcTile(NPC npc) {
        return new Anchor(Kind.NPC_TILE, npc.getIndex(), npc.getSpawnCycle(), 0, 0, 0);
    }

    /**
     * The same anchor shifted along the ground.
     *
     * @param east  local units east
     * @param north local units north
     * @return the shifted anchor
     */
    public Anchor offset(int east, int north) {
        return new Anchor(kind, index, spawnCycle, dx + east, dy + north, lift);
    }

    /**
     * The same anchor raised above the ground.
     *
     * @param height local units up
     * @return the raised anchor
     */
    public Anchor lift(int height) {
        return new Anchor(kind, index, spawnCycle, dx, dy, lift + height);
    }
}
