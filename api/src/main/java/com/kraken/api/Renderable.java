package com.kraken.api;

/**
 * Something the client draws in the scene, as RuneLite's {@code Renderable}. RuneLite's is about models, which Kraken
 * can't read; this carries the one property Kraken reads for everything drawn in the scene.
 */
public interface Renderable {

    /**
     * Whether a Lua plugin has hidden this (Lua's {@code render3D(false)}). Kraken extra.
     *
     * @return true if the client won't draw its model
     */
    boolean isHidden();
}
