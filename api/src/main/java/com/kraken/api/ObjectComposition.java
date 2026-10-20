package com.kraken.api;

/**
 * An object's (scenery's) definition, as RuneLite's {@code ObjectComposition}: the client's loc type, decoded from its
 * config archive as the client loads it. Get one from {@link Client#getObjectDefinition}.
 */
public interface ObjectComposition extends ParamHolder {

    /**
     * The object id.
     *
     * @return the id
     */
    int getId();

    /**
     * The object's name.
     *
     * @return the name, "" for a type without one
     */
    String getName();

    /**
     * The object's actions ("Open", "Chop down", …), with conditional actions resolved against the current vars as the
     * client resolves them.
     *
     * @return five entries, index 0 being the first action; null for an empty slot, as in RuneLite
     */
    String[] getActions();

    /**
     * The object's size along x, from its definition: before any rotation, as in RuneLite. A placed object turned a
     * quarter has its x and y sizes swapped.
     *
     * @return the width in tiles
     */
    int getSizeX();

    /**
     * The object's size along y (north), from its definition: before any rotation, as in RuneLite.
     *
     * @return the length in tiles
     */
    int getSizeY();

    /**
     * Whether the object blocks movement. Kraken extra.
     *
     * @return true if it blocks walking
     */
    boolean blocksWalk();

    /**
     * Whether the object blocks projectiles. Kraken extra.
     *
     * @return true if it blocks ranged attacks and line of sight
     */
    boolean blocksRange();
}
