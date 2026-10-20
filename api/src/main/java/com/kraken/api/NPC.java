package com.kraken.api;

/**
 * A non-player character, as RuneLite's {@code NPC}. See {@link Actor} for how a handle behaves once the NPC is gone.
 */
public interface NPC extends Actor {

    /**
     * The NPC's base type id (Lua's {@code npcTypeId}), as RuneLite's.
     *
     * @return the type id (the wiki id); the last one read once the NPC is gone; -1 if it was never read
     */
    int getId();

    /**
     * The NPC's index, the key the client keeps it under.
     *
     * @return the index
     */
    int getIndex();

    /**
     * The NPC's base type definition.
     *
     * @return the definition of {@link #getId()}, or null if the id is unknown or config types can't be read
     */
    NPCComposition getComposition();

    /**
     * The definition the NPC currently shows: for a multi-NPC, the child type its varbit or varp picks.
     *
     * @return the shown definition; the base definition if the type has no transform; null if the transform picks no
     *         type (the NPC is hidden) or it can't be read
     */
    NPCComposition getTransformedComposition();

    /**
     * Whether the NPC is a follower (such as a pet) of any player. Kraken extra, mirrors Lua {@code Npc.isFollower}.
     *
     * @return true for a follower; false once the NPC is gone
     */
    boolean isFollower();

    /**
     * The client cycle (20 ms units) the NPC was created on. Kraken extra, mirrors Lua {@code npcCreationCycle}.
     *
     * @return the creation cycle; the last one read once the NPC is gone; -1 if it was never read
     */
    int getSpawnCycle();
}
