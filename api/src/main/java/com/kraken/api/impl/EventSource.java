package com.kraken.api.impl;

/**
 * Posts the RuneLite-shaped game events ({@code com.kraken.api.events}) on an {@code EventBus}, by reading the game
 * once per call and comparing it with the previous read. {@code KrakenApi}'s poll loop calls it about every 20 ms on
 * the poll thread; get one from {@link Adapters#eventSource}.
 */
public interface EventSource {

    /**
     * Reads the game once and posts what changed since the previous call: the tracked events, then {@code GameTick}
     * if the tick counter moved, then {@code ClientTick}. Poll thread only.
     *
     * @return true if a game tick happened since the previous call
     */
    boolean poll();
}
