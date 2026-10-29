package com.kraken.api.internal.event;

import com.kraken.api.events.FocusChanged;

/**
 * {@link FocusChanged} when {@code Client.isFocused()} changes, compared every poll. The client keeps no focus state
 * and raises nothing for it, so there is no trigger. Tracking that starts posts nothing.
 */
final class FocusTracker extends Tracker {

    private boolean focused;

    FocusTracker(TrackerContext context) {
        super(context, FocusChanged.class);
    }

    @Override
    boolean baseline(boolean replay) {
        focused = client.isFocused();
        return true;
    }

    @Override
    void update(boolean ticked) {
        boolean now = client.isFocused();
        if (now != focused) {
            focused = now;
            bus.post(new FocusChanged(now));
        }
    }

    @Override
    void forget() {
        focused = false;
    }
}
