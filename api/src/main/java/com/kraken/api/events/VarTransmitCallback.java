package com.kraken.api.events;

/**
 * Mirrors the Lua {@code OnVarTransmitCallback}: {@code (var_table) => void}, called for
 * {@link Events#ON_VAR_TRANSMIT} with the ids of the varps that changed. Read their values with
 * {@code ClientState.getVarp}.
 */
@FunctionalInterface
public interface VarTransmitCallback {
    void onVarTransmit(int[] varpIds);
}
