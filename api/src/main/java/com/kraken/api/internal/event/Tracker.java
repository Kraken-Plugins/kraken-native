package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.eventbus.EventBus;

import java.util.Set;

/**
 * One kind of state, compared poll to poll while its events have subscribers.
 *
 * <p>Demand-driven: a tracker only reads while the bus has subscribers for its events, and forgets its baseline when
 * they all leave. A tracker that starts on the first poll (when the API attaches) replays the current state as events;
 * one that starts later takes a silent baseline.
 */
abstract class Tracker {

    /**
     * The client the tracker reads.
     */
    final Client client;

    /**
     * The bus the tracker posts on.
     */
    final EventBus bus;

    private final TrackerContext context;
    private final Class<?>[] events;
    private boolean tracking;
    private boolean replayPending = true;
    private boolean triggered;

    /**
     * Creates the tracker.
     *
     * @param context the shared poll state
     * @param events  the event kinds that decide whether the tracker reads
     */
    Tracker(TrackerContext context, Class<?>... events) {
        this.context = context;
        this.client = context.client();
        this.bus = context.bus();
        this.events = events;
    }

    /**
     * Whether the previous poll saw a game tick.
     *
     * @return true if the previous poll ticked
     */
    final boolean afterTick() {
        return context.afterTick();
    }

    /**
     * The native event names that drive this tracker, so a push read happens when one arrives instead of on the
     * timer. None by default: such a tracker only ever reads on the poll.
     *
     * @return the native event names {@link #onTrigger} should be routed for
     */
    Set<String> triggerNames() {
        return Set.of();
    }

    /**
     * Records a native trigger for this tracker, to apply at the next {@link #run}. The default just notes one
     * arrived ({@link #triggered()}); trackers that read a payload override this. Poll thread only.
     *
     * @param event the raise routed here by {@link EventSource#onNative}
     */
    void onTrigger(NativeEvent event) {
        triggered = true;
    }

    /**
     * Whether a native trigger arrived since the last poll. A tracker that only reads on a tick or when its scene
     * is due also reads when this is set.
     *
     * @return true if {@link #onTrigger} ran since the last {@link #run}
     */
    final boolean triggered() {
        return triggered;
    }

    /**
     * Starts, updates or stops tracking, depending on whether anyone subscribes to the events. Clears the trigger
     * afterwards, so a trigger drives at most the next poll.
     *
     * @param ticked whether a game tick happened in this poll
     */
    final void run(boolean ticked) {
        try {
            boolean wanted = false;
            for (Class<?> event : events) {
                wanted |= bus.hasSubscribers(event);
            }
            if (!wanted) {
                if (tracking) {
                    tracking = false;
                    forget();
                }
                replayPending = false;
                return;
            }
            if (!tracking) {
                if (baseline(replayPending)) {
                    tracking = true;
                    replayPending = false;
                }
                return;
            }
            update(ticked);
        } finally {
            triggered = false;
        }
    }

    /**
     * Reads the state to compare later polls with.
     *
     * @param replay true when tracking starts as the API attaches: post the state as events
     * @return false if the state can't be read yet, to try again next poll
     */
    abstract boolean baseline(boolean replay);

    /**
     * Reads the state and posts what changed since the last read.
     *
     * @param ticked whether a game tick happened in this poll
     */
    abstract void update(boolean ticked);

    /**
     * Drops the baseline: nobody listens any more.
     */
    abstract void forget();
}
