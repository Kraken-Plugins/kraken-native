package com.kraken.api.config;

/** Mirrors the Lua {@code osrs.TypecodeEntityType} enum: the kind of entity a type or typecode describes. */
public enum TypecodeEntityType {
    INVALID(-1), PLAYER(0), NPC(1), LOC(2), OBJ(3);

    private final int value;

    TypecodeEntityType(int value) {
        this.value = value;
    }

    /** The value in the Lua API. */
    public int value() {
        return value;
    }

    /** The entity type with this value, or {@link #INVALID}. */
    public static TypecodeEntityType of(int value) {
        for (TypecodeEntityType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return INVALID;
    }
}
