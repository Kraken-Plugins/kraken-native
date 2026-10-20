package com.kraken.api.events;

/**
 * Posted on the client thread once per API poll, about every 20 ms. It follows the poll loop, not the game's own
 * cycle, so the two drift apart. The API posts one shared instance.
 */
public class ClientTick {
}
