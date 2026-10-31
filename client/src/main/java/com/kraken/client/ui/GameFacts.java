package com.kraken.client.ui;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.WorldType;
import com.kraken.api.actor.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Kraken page shows about the game, read on the API poll thread and shown on the EDT.
 *
 * @param state  the game state
 * @param player the local player's name, or null while not logged in or unreadable
 * @param world  the world number, or -1 while not logged in or unknown
 * @param types  the world's types, empty while not logged in
 */
record GameFacts(GameState state, String player, int world, Set<WorldType> types) {

    /**
     * Before the API attaches.
     */
    static final GameFacts NONE = new GameFacts(GameState.UNKNOWN, null, -1, Set.of());

    /**
     * Reads the facts from the game.
     *
     * @param client the attached API's client
     * @return the facts; the player and world only while logged in
     */
    static GameFacts read(Client client) {
        GameState state = client.getGameState();
        if (state != GameState.LOGGED_IN) {
            return new GameFacts(state, null, -1, Set.of());
        }
        Player local = client.getLocalPlayer();
        String name = local != null ? local.getName() : null;
        return new GameFacts(state, name == null || name.isBlank() ? null : name, client.getWorld(),
                Collections.unmodifiableSet(EnumSet.copyOf(client.getWorldType())));
    }

    /**
     * The game state in words.
     *
     * @return the state, such as {@code Logged in} or {@code Login screen}
     */
    String stateText() {
        String words = state.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * The world's types in words: members or free-to-play first, then any others in flag order.
     *
     * @return the types, such as {@code Members, PvP}, or null while not logged in
     */
    String typesText() {
        if (state != GameState.LOGGED_IN) {
            return null;
        }
        List<String> words = new ArrayList<>();
        words.add(types.contains(WorldType.MEMBERS) ? "Members" : "Free-to-play");
        for (WorldType type : types) {
            if (type != WorldType.MEMBERS) {
                words.add(typeText(type));
            }
        }
        return String.join(", ", words);
    }

    /**
     * The Jagex account the game was started with, from the variables the Jagex Launcher would set
     * ({@code docs/jagex-accounts.md}). Kraken passes its own environment to the game, so these are the game's.
     *
     * @param env the environment
     * @return the character's display name, or null if the session, character and display name aren't all set
     */
    static String jagexAccount(Map<String, String> env) {
        String name = env.get("JX_DISPLAY_NAME");
        if (blank(env.get("JX_SESSION_ID")) || blank(env.get("JX_CHARACTER_ID")) || blank(name)) {
            return null;
        }
        return name.trim();
    }

    private static String typeText(WorldType type) {
        return switch (type) {
            case MEMBERS -> "Members";
            case PVP -> "PvP";
            case BOUNTY -> "Bounty";
            case PVP_ARENA -> "PvP Arena";
            case SKILL_TOTAL -> "Skill total";
            case QUEST_SPEEDRUNNING -> "Quest speedrunning";
            case HIGH_RISK -> "High risk";
            case LAST_MAN_STANDING -> "Last Man Standing";
            case BETA_WORLD -> "Beta";
            case NOSAVE_MODE -> "No save";
            case TOURNAMENT_WORLD -> "Tournament";
            case FRESH_START_WORLD -> "Fresh start";
            case DEADMAN -> "Deadman";
            case SEASONAL -> "Seasonal";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
