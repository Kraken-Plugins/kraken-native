package com.kraken.api.internal.event;

import com.kraken.api.GameState;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.internal.MapCoord;

import java.util.Set;

/**
 * {@link GameStateChanged} for every change of the raw state, plus a loading/logged-in pair when the map base moved
 * between two polls that both saw the logged-in state. A {@code LOGGED_IN} is held until {@link #postHeld()}, which
 * the poll calls after the scene's spawns.
 */
final class GameStateTracker extends Tracker {

    private int lastState;
    private MapCoord lastBase;
    private boolean loggedInHeld;

    GameStateTracker(TrackerContext context) {
        super(context, GameStateChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.SET_GAME_STATE, "ON_GAME_STATE_CHANGED");
    }

    @Override
    boolean baseline(boolean replay) {
        int raw = client.rawGameState();
        if (raw < 0) {
            return false;
        }
        lastState = raw;
        lastBase = client.mapBase();
        if (replay) {
            post(GameState.of(raw));
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        int raw = client.rawGameState();
        if (raw < 0) {
            return;
        }
        MapCoord base = client.mapBase();
        int before = lastState;
        boolean loadMissed = raw == before && raw == GameState.LOGGED_IN.getState() && base != null
                && lastBase != null && !base.equals(lastBase);
        lastState = raw;
        if (base != null) {
            lastBase = base;
        }
        if (raw != before) {
            post(GameState.of(raw));
        } else if (loadMissed) {
            post(GameState.LOADING);
            post(GameState.LOGGED_IN);
        }
    }

    @Override
    void forget() {
        lastBase = null;
        loggedInHeld = false;
    }

    /**
     * Posts the {@code LOGGED_IN} this poll held back, if any.
     */
    void postHeld() {
        if (loggedInHeld) {
            loggedInHeld = false;
            send(GameState.LOGGED_IN);
        }
    }

    private void post(GameState gameState) {
        if (gameState == GameState.LOGGED_IN) {
            loggedInHeld = true;
        } else {
            send(gameState);
        }
    }

    private void send(GameState gameState) {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(gameState);
        bus.post(event);
    }
}
