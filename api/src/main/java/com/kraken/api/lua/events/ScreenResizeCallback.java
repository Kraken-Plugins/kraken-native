package com.kraken.api.lua.events;

/**
 * Mirrors the Lua {@code OnScreenResizeCallback}: {@code () => void}, called for {@link Events#ON_SCREEN_RESIZE}.
 */
@FunctionalInterface
public interface ScreenResizeCallback {

    /**
     * The client was resized. {@code Graphics.clientDimensions()} returns the new size.
     */
    void onScreenResize();
}
