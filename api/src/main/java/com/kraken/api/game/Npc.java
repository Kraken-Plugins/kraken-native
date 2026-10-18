package com.kraken.api.game;

/**
 * An immutable snapshot of one loaded NPC, read at a moment in time. Mirrors the data the Lua
 * {@code osrs.Npc} / {@code ClientOp.getNpcObj} surface exposes.
 *
 * @param index    the NPC's index in the world (its key in the NPC table; what {@code npcName(id)} takes)
 * @param typeId   its definition id ({@code NpcType.id}; the id you'd look up on the wiki), or -1 if unknown
 * @param name     its display name
 * @param location its tile, or null if it could not be read
 */
public record Npc(int index, int typeId, String name, MapCoord location) {}
