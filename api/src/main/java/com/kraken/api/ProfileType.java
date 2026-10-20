package com.kraken.api;

/**
 * The kind of world the account is logged in to, as the client decodes it from the world flags. Mirrors Lua
 * {@code ClientState.ProfileType}. The same flags are read as {@link WorldType}s ({@link Client#getWorldType()}).
 */
public enum ProfileType {
    STANDARD,
    BETA,
    DEADMAN,
    PVP_ARENA,
    QUEST_SPEEDRUNNING,
    SEASONAL
}
