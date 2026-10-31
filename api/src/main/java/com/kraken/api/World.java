package com.kraken.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.EnumSet;
import java.util.Set;

/**
 * A game world, as the world list describes it: its number, kinds, server, activity, location and how many players
 * are on it. {@link Client#hopToWorld} takes one. Build one with {@link #builder()}; only the id matters for a hop.
 */
@Getter
@EqualsAndHashCode
@ToString
public final class World {

    /**
     * The world's number, as the world switcher shows it, e.g. 302.
     */
    private final int id;

    /**
     * The kinds of world it is.
     */
    @Getter(lombok.AccessLevel.NONE)
    private final EnumSet<WorldType> types;

    /**
     * The world's server, e.g. {@code oldschool2.runescape.com}; empty if unknown.
     */
    private final String address;

    /**
     * What the world is for, e.g. "Trade - Free" or "2000 skill total"; "-" for none, empty if unknown.
     */
    private final String activity;

    /**
     * Where the world's server is, as the world list numbers locations: 0 the United States, 1 the United Kingdom,
     * 3 Australia, 7 Germany and so on; -1 if unknown.
     */
    private final int location;

    /**
     * How many players were on the world when the list was read; -1 for a world that is offline.
     */
    private final int playerCount;

    /**
     * Makes a world.
     *
     * @param id          the world's number
     * @param types       the kinds of world it is, or null for none
     * @param address     the world's server, or null if unknown
     * @param activity    what the world is for, or null if unknown
     * @param location    the world list's location number, or -1 if unknown
     * @param playerCount the players on it, or -1 if offline
     */
    @Builder
    private World(int id, Set<WorldType> types, String address, String activity, int location, int playerCount) {
        this.id = id;
        this.types = types == null || types.isEmpty() ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(types);
        this.address = address == null ? "" : address;
        this.activity = activity == null ? "" : activity;
        this.location = location;
        this.playerCount = playerCount;
    }

    /**
     * The kinds of world it is.
     *
     * @return a copy of the world's types
     */
    public EnumSet<WorldType> getTypes() {
        return EnumSet.copyOf(types);
    }

    /**
     * Whether the world is one kind of world.
     *
     * @param type the kind
     * @return true if the world's types include it
     */
    public boolean hasType(WorldType type) {
        return types.contains(type);
    }
}
