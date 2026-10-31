package com.kraken.api.internal;

/**
 * The two components the script engine's ops act on at one moment, by id, as the native hook captured them for a
 * script event: the active component, which {@code cc_*} ops change, and the "dot" component, which {@code .cc_*} ops
 * change. Scripts point them at a component with {@code cc_find} or {@code cc_create} (and the dot forms), and the
 * engine keeps them between scripts.
 *
 * @param activeId    the active component's packed id, or -1 for none
 * @param activeIndex its index among its static parent's dynamic children, or -1 for a static component
 * @param dotId       the dot component's packed id, or -1 for none
 * @param dotIndex    its index, or -1 for a static component
 */
public record ScriptComponents(int activeId, int activeIndex, int dotId, int dotIndex) {

    /**
     * Neither component set, or none captured.
     */
    public static final ScriptComponents NONE = new ScriptComponents(-1, -1, -1, -1);
}
