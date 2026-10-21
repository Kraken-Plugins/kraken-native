package com.kraken.api.highlight;

/**
 * What a highlight channel lights up. Mirrors {@code osrs.ClientOp.HighlightCategory}; each category has its own 32
 * channels, so NPC channel 0 and tile channel 0 are different channels.
 */
public enum HighlightCategory {

    /**
     * One NPC, by its index and creation cycle.
     */
    NPC("NPC", 0, "highlightNpc"),

    /**
     * Every NPC of a type, by NPC id.
     */
    NPC_TYPE("NPCTYPE", 1, "highlightNpcType"),

    /**
     * One object (scenery), by its id and the tile it was placed on.
     */
    LOC("LOC", 2, "highlightLoc"),

    /**
     * Every object of a type, by object id.
     */
    LOC_TYPE("LOCTYPE", 3, "highlightLocType"),

    /**
     * One ground item stack, by its item id and tile.
     */
    OBJ("OBJ", 4, "highlightObj"),

    /**
     * Every ground item of a type, by item id.
     */
    OBJ_TYPE("OBJTYPE", 5, "highlightObjType"),

    /**
     * One player, by display name.
     */
    PLAYER("PLAYER", 6, "highlightPlayer"),

    /**
     * One tile.
     */
    TILE("TILE", 7, "highlightTile");

    private final String luaName;
    private final int defaultId;
    private final String function;

    HighlightCategory(String luaName, int defaultId, String function) {
        this.luaName = luaName;
        this.defaultId = defaultId;
        this.function = function;
    }

    /**
     * The category's name in the client's enum.
     *
     * @return e.g. {@code "NPCTYPE"}
     */
    public String luaName() {
        return luaName;
    }

    /**
     * The category's value when the mapping lacks the enum.
     *
     * @return the value in build 241.3
     */
    int defaultId() {
        return defaultId;
    }

    /**
     * The mapping key of the function that turns a highlight of this category on.
     *
     * @return e.g. {@code "highlightNpcOn"}
     */
    String onFunction() {
        return function + "On";
    }

    /**
     * The mapping key of the function that turns a highlight of this category off.
     *
     * @return e.g. {@code "highlightNpcOff"}
     */
    String offFunction() {
        return function + "Off";
    }
}
