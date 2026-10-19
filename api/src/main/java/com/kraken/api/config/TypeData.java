package com.kraken.api.config;

/**
 * Mirrors the Lua {@code osrs.TypeData} interface: what every decoded config type ({@link ObjType}, {@link NpcType},
 * {@link LocType}) shares.
 */
public interface TypeData {

    /** The type's id, the same id as in the game's gamevals (and on the wiki). */
    int getId();

    /** The type's name ("null" for types without one, as the client stores it). */
    String getName();

    /** Which kind of entity this type describes. */
    TypecodeEntityType getEntityType();
}
