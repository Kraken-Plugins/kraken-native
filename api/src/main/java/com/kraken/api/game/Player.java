package com.kraken.api.game;

/**
 * An immutable snapshot of one loaded player. Mirrors the Lua {@code osrs.Player} /
 * {@code ClientOp.getPlayerObj} surface.
 *
 * @param index    the player's index in the world (its key in the player table)
 * @param name     the player's display name
 * @param location the player's tile, or null if it could not be read
 */
public record Player(int index, String name, MapCoord location) {}
