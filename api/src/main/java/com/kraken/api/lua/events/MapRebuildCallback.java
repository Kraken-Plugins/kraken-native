package com.kraken.api.lua.events;

/**
 * Mirrors the Lua {@code OnMapRebuildPreCallback} and {@code OnMapRebuildPostCallback}: {@code () => void}, used for
 * both {@link Events#ON_MAP_REBUILD_PRE} and {@link Events#ON_MAP_REBUILD_POST}.
 */
@FunctionalInterface
public interface MapRebuildCallback {

    /**
     * The map started (pre) or finished (post) loading a new area.
     */
    void onMapRebuild();
}
