package com.kraken.api.config;

/**
 * Mirrors the Lua {@code osrs.TypeData} interface: what every decoded config type ({@link ObjType}, {@link NpcType},
 * {@link LocType}) shares.
 */
public interface TypeData {

    /**
     * The type's id, the same id as in the game's gamevals (and on the wiki).
     *
     * @return the id the type was looked up with
     */
    int getId();

    /**
     * The type's name.
     *
     * @return the name, "" for a type without a definition or name ("null" where the client's data says so)
     */
    String getName();

    /**
     * Which kind of entity this type describes.
     *
     * @return {@link TypecodeEntityType#OBJ}, {@link TypecodeEntityType#NPC} or {@link TypecodeEntityType#LOC}
     */
    TypecodeEntityType getEntityType();
}
