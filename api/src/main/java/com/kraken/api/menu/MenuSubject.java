package com.kraken.api.menu;

/**
 * What a menu row is about, as the client's entries record it ({@code enums.minimenuSubject}). A {@link MenuMatch}
 * can match rows by it alone, such as every row on an NPC.
 */
public enum MenuSubject {

    /**
     * No subject: "Cancel", and the rows a plugin adds.
     */
    NONE("NONE"),

    /**
     * A tile: "Walk here".
     */
    TILE("TILE"),

    /**
     * An NPC.
     */
    NPC("NPC"),

    /**
     * A loc (a game object).
     */
    LOC("LOC"),

    /**
     * A ground item.
     */
    OBJ("OBJ"),

    /**
     * A player.
     */
    PLAYER("PLAYER"),

    /**
     * An interface component.
     */
    COMPONENT("COMPONENT");

    private final String mappingName;

    MenuSubject(String mappingName) {
        this.mappingName = mappingName;
    }

    /**
     * The kind's name in the mapping's {@code enums.minimenuSubject} table.
     *
     * @return the name
     */
    public String mappingName() {
        return mappingName;
    }
}
