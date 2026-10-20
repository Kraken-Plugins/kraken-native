package com.kraken.api.lua;

import com.kraken.api.lua.types.NpcType;

/**
 * Mirrors the Lua {@code osrs.Npc} object that {@code ClientOp.getNpcObj} returns: a live handle on an NPC index (see
 * {@link Entity} for what "live" means). Get one from {@link ClientOp#getNpcObj} or {@link ClientOp#getNpcs}.
 *
 * <p>{@link ClientOp#pinNpc} makes a pinned handle instead, a Java extra: it stays on the NPC that held the index when
 * it was made, and reads as "not loaded" once another NPC takes the index.
 */
public final class Npc implements Entity {

    private final ActorReader actors;
    private final int index;
    /**
     * The NPC this handle is pinned to, or 0 for a handle that follows the index.
     */
    private final long pinnedAddress;
    /**
     * The pinned NPC's creation cycle: a new NPC can be allocated where the old one was.
     */
    private final int pinnedCycle;

    Npc(ActorReader actors, int index) {
        this(actors, index, 0, 0);
    }

    Npc(ActorReader actors, int index, long pinnedAddress, int pinnedCycle) {
        this.actors = actors;
        this.index = index;
        this.pinnedAddress = pinnedAddress;
        this.pinnedCycle = pinnedCycle;
    }

    /**
     * The NPC at this handle's index, if it's the one the handle is for.
     *
     * @return its address; 0 if nothing is loaded at the index, or (pinned) another NPC has taken it
     */
    private long address() {
        long address = actors.npc(index);
        if (pinnedAddress != 0 && (address != pinnedAddress || actors.npcCreationCycle(address) != pinnedCycle)) {
            return 0;
        }
        return address;
    }

    @Override
    public int index() {
        return index;
    }

    @Override
    public boolean exists() {
        return address() != 0;
    }

    /**
     * Mirrors {@code getName}: the NPC's own name if set, else its type's (following a multi-NPC transform).
     *
     * @return the NPC's name, or "" if it isn't loaded
     */
    @Override
    public String getName() {
        return actors.npcName(address());
    }

    @Override
    public int isInteracting() {
        return actors.interacting(address());
    }

    @Override
    public int getInteractingType() {
        return actors.interactingType(address());
    }

    @Override
    public boolean isDead() {
        return actors.dead(address());
    }

    @Override
    public boolean isHealthbarRendered() {
        return actors.healthbarRendered(address());
    }

    @Override
    public boolean isHeadbarEmpty() {
        return actors.headbarEmpty(address());
    }

    @Override
    public boolean isRendered3D() {
        return actors.rendered3D(address());
    }

    @Override
    public boolean isRendered2D() {
        return actors.rendered2D(address());
    }

    @Override
    public MapCoord getCoord() {
        return actors.coord(address());
    }

    @Override
    public FineCoord getCoordFine() {
        return actors.fineCoord(address());
    }

    /**
     * Mirrors {@code getType}: the NPC's category number.
     *
     * @return the category of the NPC's base type, or -1 if the NPC isn't loaded
     */
    public int getType() {
        return actors.npcCategory(address());
    }

    /**
     * Mirrors {@code getTypeData}: the NPC's base type definition, decoded by id as the client loads it. (The native
     * method crashes for a missing NPC; this returns null.)
     *
     * @return the base type's definition, or null if the NPC isn't loaded or config types can't be read
     */
    public NpcType getTypeData() {
        int id = actors.npcTypeId(address());
        return id < 0 ? null : actors.configs.npc(id);
    }

    /**
     * Mirrors {@code isFollower}.
     *
     * @return true if the NPC is a follower (such as a pet) of any player; false if it isn't loaded
     */
    public boolean isFollower() {
        return actors.npcIsFollower(address());
    }

    /**
     * Mirrors {@code isLocalFollower}.
     *
     * @return true if the NPC is loaded and is the local player's follower
     */
    public boolean isLocalFollower() {
        return exists() && actors.npcIsLocalFollower(index);
    }

    /**
     * The NPC's base type id ({@code ClientOp.npcTypeId}). Not a Lua method.
     *
     * @return the base type id (the wiki id), or -1 if the NPC isn't loaded
     */
    public int getTypeId() {
        return actors.npcTypeId(address());
    }

    /**
     * The type the NPC currently shows: for a multi-NPC, the child type its varbit or varp picks (the type
     * {@link #getName} takes the name from). Not a Lua method.
     *
     * @return the shown type's id: the base type's id if it has no transform, -1 if the transform picks no type (the
     *         NPC is hidden), or -1 if the NPC isn't loaded
     */
    public int getTransformedTypeId() {
        return actors.npcShownTypeId(address());
    }

    /**
     * {@code ClientOp.npcUid}. Not a Lua method.
     *
     * @return type id × 65536 + index, or -1 if the NPC isn't loaded
     */
    public int getUid() {
        return actors.npcUid(address());
    }

    /**
     * {@code ClientOp.npcCreationCycle}. Not a Lua method.
     *
     * @return the client cycle (20 ms units) the NPC was created on, or -1 if it isn't loaded
     */
    public int getCreationCycle() {
        return actors.npcCreationCycle(address());
    }

    /**
     * Two handles are equal if they read the same thing: the same index, and for pinned handles the same NPC.
     *
     * @param o the other object
     * @return true for a handle on the same index with the same pin (or neither pinned)
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Npc other && other.index == index && other.actors == actors
                && other.pinnedAddress == pinnedAddress && other.pinnedCycle == pinnedCycle;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return pinnedAddress == 0 ? "Npc[" + index + "]" : "Npc[" + index + " pinned]";
    }
}
