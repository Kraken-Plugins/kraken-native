package com.kraken.api.events;

import com.kraken.api.GameState;
import lombok.Data;

/**
 * The client's game state changed, as RuneLite's {@code GameStateChanged}. Posted for every change of the raw state,
 * not only Lua's five {@code GameStateTrigger}s.
 *
 * <p>A map load short enough to fall between two polls is caught by the map's base moving, and posted as
 * {@link GameState#LOADING} then {@link GameState#LOGGED_IN}, since plugins clear their scene caches on loading. A
 * load that doesn't move the base and falls between two polls isn't seen.
 */
@Data
public class GameStateChanged {

    /**
     * The new game state.
     */
    private GameState gameState;
}
