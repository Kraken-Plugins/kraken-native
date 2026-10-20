package com.kraken.api.lua.events;

/**
 * One updated stat in an {@link Events#ON_STAT_TRANSMIT} event, with the fields of the Lua stat table.
 *
 * @param id    the stat id ({@code ClientState.Stats})
 * @param xp    its XP
 * @param value its current (effective, possibly boosted or drained) level
 * @param max   its base level, from XP
 */
public record StatUpdate(int id, int xp, int value, int max) {
}
