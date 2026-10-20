package com.kraken.api.events;

/**
 * Posted on the client thread once per API poll, about every 20 ms, as RuneLite's {@code ClientTick} is once per
 * client cycle. It follows the poll loop, not the game's own cycle, so the two drift apart. The API posts one shared
 * instance.
 */
public class ClientTick {
}
