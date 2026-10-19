package com.kraken.api.game;

import com.kraken.api.config.NpcType;

/**
 * Mirrors the Lua {@code osrs.Npc} object that {@code ClientOp.getNpcObj} returns: a live handle on an NPC index (see
 * {@link Entity} for what "live" means). Get one from {@link Client#getNpcObj} or {@link Client#getNpcs}.
 */
public final class Npc implements Entity {

    private final ActorReader actors;
    private final int index;

    Npc(ActorReader actors, int index) {
        this.actors = actors;
        this.index = index;
    }

    private long address() {
        return actors.npc(index);
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

    @Override
    public boolean equals(Object o) {
        return o instanceof Npc other && other.index == index && other.actors == actors;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return "Npc[" + index + "]";
    }
}
