package com.kraken.api.lua.events;

/**
 * One of the client's events, such as {@link Events#ON_GAME_TICK}: its Lua event id plus the callback type it calls,
 * so {@link Events#subscribe} can check a callback's shape at compile time.
 *
 * @param <C> the callback interface subscribers implement
 */
public final class Event<C> {
    private final int id;
    private final String name;

    Event(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /** The event's id in the Lua API ({@code osrs.Events.<name>}). */
    public int id() {
        return id;
    }

    /** The event's Lua name, e.g. {@code "ON_GAME_TICK"}. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
