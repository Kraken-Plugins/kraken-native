package com.kraken.api.events;

/** Mirrors the Lua {@code OnGameTickCallback}: {@code () => void}. */
@FunctionalInterface
public interface GameTickCallback {
    void onTick();
}
