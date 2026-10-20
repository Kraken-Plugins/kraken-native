package com.kraken.api.events;

/**
 * A server tick (about every 600 ms), as RuneLite's {@code GameTick}. Posted on the client thread after the tick's
 * other events (spawns, stat and container changes…), so subscribers see the tick's state as a whole.
 *
 * <p>Kraken sees the tick counter move between two polls (about 20 ms apart), so this follows the tick by up to one
 * poll. The API posts one shared instance.
 */
public class GameTick {
}
