package com.kraken.api.lua.events;

/**
 * Mirrors the Lua {@code OnGameStateChangeCallback}: {@code (game_state) => void}, called for
 * {@link Events#ON_GAME_STATE_CHANGED} with the transition that happened.
 */
@FunctionalInterface
public interface GameStateChangeCallback {
    void onGameStateChange(Events.GameStateTrigger trigger);
}
