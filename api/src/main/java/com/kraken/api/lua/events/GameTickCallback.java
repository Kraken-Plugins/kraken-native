package com.kraken.api.lua.events;

/** Mirrors the Lua {@code OnGameTickCallback}: {@code () => void}. */
@FunctionalInterface
public interface GameTickCallback {
    void onTick();
}
