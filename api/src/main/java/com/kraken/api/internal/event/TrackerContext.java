package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.eventbus.EventBus;

/**
 * The state the trackers share each poll: the client to read, the bus to post on, and whether the previous poll saw a
 * game tick. {@link EventSource} owns one and refreshes {@link #afterTick()} before it runs the trackers, so a tracker
 * that looks on the tick's poll also looks on the one after.
 */
final class TrackerContext {

    private final Client client;
    private final EventBus bus;
    private boolean afterTick;

    /**
     * Creates the context.
     *
     * @param client the client the trackers read
     * @param bus    the bus the trackers post on and read subscriber counts from
     */
    TrackerContext(Client client, EventBus bus) {
        this.client = client;
        this.bus = bus;
    }

    /**
     * The client the trackers read.
     *
     * @return the client
     */
    Client client() {
        return client;
    }

    /**
     * The bus the trackers post on, and whose subscriber counts decide whether a tracker reads.
     *
     * @return the bus
     */
    EventBus bus() {
        return bus;
    }

    /**
     * Whether the previous poll saw a game tick: the trackers that look on the tick look again on this poll.
     *
     * @return true if the previous poll ticked
     */
    boolean afterTick() {
        return afterTick;
    }

    /**
     * Sets the after-tick flag for this poll.
     *
     * @param afterTick whether the previous poll saw a game tick
     */
    void setAfterTick(boolean afterTick) {
        this.afterTick = afterTick;
    }
}
