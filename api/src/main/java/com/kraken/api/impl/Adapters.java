package com.kraken.api.impl;

import com.kraken.api.Client;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.lua.LuaApi;

/**
 * Builds the RuneLite-shaped API over the Lua mirror. {@code KrakenApi} uses it; plugins get the result from
 * {@code KrakenApi.client()} and never need this class.
 *
 * <p>The adapters behind it are package-private: plugins see only the interfaces in {@code com.kraken.api}.
 */
public final class Adapters {

    private Adapters() {
    }

    /**
     * The RuneLite-shaped client over a Lua mirror.
     *
     * @param lua the Lua mirror to read through
     * @return a client whose every method calls into {@code lua}
     */
    public static Client client(LuaApi lua) {
        return new ClientImpl(lua);
    }

    /**
     * The source of the RuneLite-shaped game events for a client this class built.
     *
     * @param client the client from {@link #client}, whose actor handles the spawn events carry
     * @param lua    the Lua mirror the client reads through
     * @param bus    the bus to post on; a kind of event is only tracked while the bus has subscribers for it
     * @return the event source; its first poll replays the current state (see {@code RuneLiteEventSource})
     * @throws IllegalArgumentException if {@code client} wasn't built by {@link #client}
     */
    public static EventSource eventSource(Client client, LuaApi lua, EventBus bus) {
        if (!(client instanceof ClientImpl impl)) {
            throw new IllegalArgumentException("Not a client from Adapters.client: " + client.getClass().getName());
        }
        return new RuneLiteEventSource(impl, lua, bus);
    }
}
