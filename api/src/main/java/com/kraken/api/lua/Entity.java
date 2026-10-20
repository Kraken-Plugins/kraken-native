package com.kraken.api.lua;

/**
 * Mirrors the Lua {@code osrs.Entity} interface that {@link Npc} and {@link Player} implement.
 *
 * <p>Like the Lua objects, an entity is a live handle on an index, not a snapshot: every call looks the index up in
 * the current world again, so it reads the game as it is now, and if nothing is loaded at that index any more the
 * methods return their "missing" values (false, -1, ""). If the client reuses the index for another actor, the handle
 * follows the new one, as the Lua object does.
 */
public interface Entity {

    /**
     * What the {@code target} of {@link #getInteractingType} is: nothing.
     */
    int TARGET_NONE = 0;

    /**
     * The interaction target is an NPC; {@link #isInteracting} is its index.
     */
    int TARGET_NPC = 1;

    /**
     * The interaction target is a player; {@link #isInteracting} is its index.
     */
    int TARGET_PLAYER = 2;

    /**
     * The interaction target is a world entity (such as a boat); {@link #isInteracting} is its id.
     */
    int TARGET_WORLD_ENTITY = 3;

    /**
     * The index the {@code ClientOp} functions take for this entity. Not a Lua method.
     *
     * @return the entity's index (an NPC index or a player index)
     */
    int index();

    /**
     * Whether something is loaded at this entity's index right now. Not a Lua method.
     *
     * @return true if the top-level world has an actor at this index
     */
    boolean exists();

    /**
     * Mirrors {@code getName}.
     *
     * @return the entity's name, or "" if it isn't loaded
     */
    String getName();

    /**
     * Mirrors {@code isInteracting}. The index alone doesn't say whether it's an NPC or a player;
     * {@link #getInteractingType} does.
     *
     * @return the index (or world entity id) of whatever this entity is interacting with, or -1 for nothing
     */
    int isInteracting();

    /**
     * What {@link #isInteracting} points at. Not a Lua method.
     *
     * @return one of the {@code TARGET_*} constants; {@link #TARGET_NONE} if the entity isn't loaded
     */
    int getInteractingType();

    /**
     * Mirrors {@code isDead}: the entity shows a health bar and that bar is empty.
     *
     * @return true if both {@link #isHealthbarRendered} and {@link #isHeadbarEmpty} hold
     */
    boolean isDead();

    /**
     * Mirrors {@code isHealthbarRendered}.
     *
     * @return true if the entity has a health bar showing
     */
    boolean isHealthbarRendered();

    /**
     * Mirrors {@code isHeadbarEmpty}.
     *
     * @return true if one of the entity's head bars is currently showing as empty
     */
    boolean isHeadbarEmpty();

    /**
     * Mirrors {@code isRendered3D}. The Lua method reports what that Lua object last set with {@code render3D}; this
     * reads the flag {@code render3D} writes on the actor, so it also sees what other plugins hid.
     *
     * @return true if the entity's model is drawn, or if the flag can't be read
     */
    boolean isRendered3D();

    /**
     * Mirrors {@code isRendered2D}. Like {@link #isRendered3D}, this reads the flag on the actor rather than the Lua
     * object's own copy.
     *
     * @return true if the entity's 2D elements (name, health bar, overhead text) are drawn, or if the flag can't be read
     */
    boolean isRendered2D();

    /**
     * The entity's tile. Not a Lua method ({@code ClientOp.npcCoord}/{@code playerCoord}).
     *
     * @return the tile on the entity's world's plane, or null if it isn't loaded
     */
    MapCoord getCoord();

    /**
     * The entity's fine position and the ground height there. Not a Lua method ({@code ClientOp.npcCoordFine}/
     * {@code playerCoordFine}).
     *
     * @return the fine position, or {@link FineCoord#MISSING} if the entity or its world isn't loaded
     */
    FineCoord getCoordFine();
}
