package com.kraken.api.events;

/**
 * Mirrors the Lua {@code OnVarbitUpdateCallback}: {@code (varbitId, varbitValue) => void}, called for
 * {@link Events#ON_VARBIT_UPDATE} once per varbit whose value changed.
 */
@FunctionalInterface
public interface VarbitUpdateCallback {

    /**
     * A varbit's value changed.
     *
     * @param varbitId    the varbit
     * @param varbitValue its new value (what {@code ClientState.getVarbit} now returns)
     */
    void onVarbitUpdate(int varbitId, int varbitValue);
}
