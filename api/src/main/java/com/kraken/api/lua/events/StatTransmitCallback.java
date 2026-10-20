package com.kraken.api.lua.events;

import java.util.List;

/**
 * Mirrors the Lua {@code OnStatTransmitCallback}: {@code (stat_table) => void}, called for
 * {@link Events#ON_STAT_TRANSMIT} with one entry per stat that changed.
 */
@FunctionalInterface
public interface StatTransmitCallback {
    void onStatTransmit(List<StatUpdate> stats);
}
