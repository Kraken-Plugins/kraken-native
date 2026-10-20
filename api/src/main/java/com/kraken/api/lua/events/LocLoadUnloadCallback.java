package com.kraken.api.lua.events;

/**
 * Called for {@link Events#ON_LOC_LOAD} and {@link Events#ON_LOC_UNLOAD}. The Lua docs have no callback type for these
 * events; this takes what the client passes its Lua subscribers: the loc's type id, its name and its layer. As in the
 * client, the loc is the active loc while the callback runs, so {@code Client.locCoord()} gives its tile.
 */
@FunctionalInterface
public interface LocLoadUnloadCallback {

    /**
     * A loc appeared in or left the scene.
     *
     * @param locTypeId the loc's type id
     * @param name      the loc type's name
     * @param layer     which slot of its tile it is in ({@code Loc.WALL}, {@code WALL_DECOR}, {@code GAME_OBJECT},
     *                  {@code GROUND_DECOR})
     */
    void onLocLoadUnload(int locTypeId, String name, int layer);
}
