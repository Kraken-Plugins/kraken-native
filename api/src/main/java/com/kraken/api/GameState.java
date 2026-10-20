package com.kraken.api;

import lombok.Getter;

/**
 * The client's game state, as RuneLite's {@code GameState}. The numbers are the client's raw {@code Client.gameState}
 * values, which are the same in the native client.
 */
public enum GameState {

    /**
     * The state couldn't be read, or holds a value this enum doesn't know.
     */
    UNKNOWN(-1),

    /**
     * The client is starting up.
     */
    STARTING(0),

    /**
     * The title (login) screen.
     */
    LOGIN_SCREEN(10),

    /**
     * The authenticator step of the login screen. Kept for RuneLite parity; whether the native client sets it is
     * untested.
     */
    LOGIN_SCREEN_AUTHENTICATOR(11),

    /**
     * Logging in, between the title screen and the world.
     */
    LOGGING_IN(20),

    /**
     * Loading the map.
     */
    LOADING(25),

    /**
     * In the world.
     */
    LOGGED_IN(30),

    /**
     * The connection was lost; the client tries to reconnect.
     */
    CONNECTION_LOST(40),

    /**
     * Hopping to another world.
     */
    HOPPING(45);

    /**
     * The client's raw value for this state.
     */
    @Getter
    private final int state;

    GameState(int state) {
        this.state = state;
    }

    /**
     * The state with a raw value.
     *
     * @param state the client's raw game state
     * @return the matching state, or {@link #UNKNOWN} for a value this enum doesn't know
     */
    public static GameState of(int state) {
        for (GameState gameState : values()) {
            if (gameState.state == state) {
                return gameState;
            }
        }
        return UNKNOWN;
    }
}
