package com.kraken.api;

/**
 * An NPC type's definition, as RuneLite's {@code NPCComposition}: decoded from the client's config archive as the
 * client loads it. Get one from {@link Client#getNpcDefinition} or {@link NPC#getComposition}.
 */
public interface NPCComposition extends ParamHolder {

    /**
     * The type id.
     *
     * @return the id (the wiki id)
     */
    int getId();

    /**
     * The type's name.
     *
     * @return the name; "null" where the client's data says so, "" for a type without one
     */
    String getName();

    /**
     * How many tiles wide (and long) the NPC is.
     *
     * @return the size in tiles
     */
    int getSize();

    /**
     * The NPC's actions ("Talk-to", "Attack", …), with conditional actions resolved against the current vars as the
     * client resolves them.
     *
     * @return five entries, index 0 being the first action; null for an empty slot, as in RuneLite
     */
    String[] getActions();

    /**
     * Whether the type is a follower, such as a pet.
     *
     * @return true for a follower
     */
    boolean isFollower();

    /**
     * The combat level shown in the menu.
     *
     * @return the combat level, or -1 if none
     */
    int getCombatLevel();

    /**
     * The type's category. Kraken extra, mirrors Lua {@code NpcType.getType}.
     *
     * @return the category id, or -1 if none
     */
    int getCategory();

    /**
     * The type's six combat stats. Kraken extra, mirrors Lua {@code NpcType.getStats}.
     *
     * @return attack, defence, strength, hitpoints, ranged and magic, in that order
     */
    int[] getStats();
}
