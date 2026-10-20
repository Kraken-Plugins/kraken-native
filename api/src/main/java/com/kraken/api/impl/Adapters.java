package com.kraken.api.impl;

import com.kraken.api.Client;
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
}
