package com.kraken.api.internal.event;

import com.kraken.api.GameState;
import com.kraken.api.internal.MapCoord;

/**
 * What the object and ground item trackers share: a scene is read in full when it is new (the replay at attach, a
 * login, a map base that moved), posting every spawn, and compared with the last read otherwise.
 */
abstract class SceneTracker extends Tracker {

    private MapCoord lastBase;
    private boolean loadPending;

    SceneTracker(TrackerContext context, Class<?>... events) {
        super(context, events);
    }

    @Override
    final boolean baseline(boolean replay) {
        int raw = client.rawGameState();
        if (raw < 0) {
            return false;
        }
        if (raw != GameState.LOGGED_IN.getState()) {
            // No scene yet: the one that loads next spawns in full
            lastBase = null;
            loadPending = true;
            clear();
            return true;
        }
        MapCoord base = client.mapBase();
        if (base == null) {
            return false;
        }
        lastBase = base;
        loadPending = false;
        load(base, replay);
        return true;
    }

    @Override
    final void update(boolean ticked) {
        int raw = client.rawGameState();
        if (raw < 0) {
            return;
        }
        if (raw != GameState.LOGGED_IN.getState()) {
            // A lost connection keeps the scene; anything else (loading, hopping, logged out) brings a new one
            loadPending |= raw != GameState.CONNECTION_LOST.getState();
            return;
        }
        MapCoord base = client.mapBase();
        if (base == null) {
            return;
        }
        if (loadPending || !base.equals(lastBase)) {
            lastBase = base;
            loadPending = false;
            load(base, true);
        } else {
            boolean full = due(ticked);
            if (full) {
                compare(base);
            }
            targeted(base, full);
        }
    }

    @Override
    final void forget() {
        lastBase = null;
        clear();
    }

    /**
     * Reads just the tiles a payload trigger pointed at, posting what changed there. The default does nothing. A
     * tracker whose trigger carries a coord overrides this; when a full {@link #compare} already ran this poll it
     * only discards the stash, which that compare already covered.
     *
     * @param base           the scene's map base
     * @param fullCompareRan whether a whole-scene {@link #compare} already ran this poll
     */
    void targeted(MapCoord base, boolean fullCompareRan) {
    }

    /**
     * Whether to compare this poll, when the scene hasn't changed.
     *
     * @param ticked whether a game tick happened in this poll
     * @return true to read and compare
     */
    abstract boolean due(boolean ticked);

    /**
     * Reads the whole scene as the new baseline, dropping the old one without despawns.
     *
     * @param base the scene's map base
     * @param post whether to post a spawn for everything in it
     */
    abstract void load(MapCoord base, boolean post);

    /**
     * Reads the scene and posts what changed since the last read.
     *
     * @param base the scene's map base, the same as the last read's
     */
    abstract void compare(MapCoord base);

    /**
     * Drops the baseline.
     */
    abstract void clear();
}
