package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.ScriptEvent;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.ScriptPostFired;
import com.kraken.api.events.ScriptPreFired;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts {@link ScriptPreFired} and {@link ScriptPostFired} for the script events the native hook reports, and keeps
 * the hook reporting only the kinds that have subscribers. Unlike the trackers it reads nothing to find its events:
 * each report is one event, posted with the stacks it captured. Poll thread only.
 */
final class ScriptEvents {

    private static final Logger log = Logger.getLogger("kraken.api");

    private final Client client;
    private final EventBus bus;
    private ScriptHook hook;

    /**
     * What the hook was last told to send: bit 0 starts, bit 1 returns; -1 when it hasn't been told since it
     * connected.
     */
    private int sent = -1;

    /**
     * Creates the poster.
     *
     * @param client the client the events' components and stacks are read through
     * @param bus    the bus to post on
     */
    ScriptEvents(Client client, EventBus bus) {
        this.client = client;
        this.bus = bus;
    }

    /**
     * Takes the connection to a hook that reports scripts, or drops it; a new one is told what to send at once.
     *
     * @param hook the hook, or null while there is none
     */
    void setHook(ScriptHook hook) {
        this.hook = hook;
        this.sent = -1;
        sync();
    }

    /**
     * Tells the hook which events to send if the subscriptions changed since it was last told.
     */
    void sync() {
        if (hook == null) {
            return;
        }
        boolean pre = bus.hasSubscribers(ScriptPreFired.class);
        boolean post = bus.hasSubscribers(ScriptPostFired.class);
        int wanted = (pre ? 1 : 0) | (post ? 2 : 0);
        if (wanted == sent) {
            return;
        }
        try {
            hook.capture(pre, post);
            sent = wanted;
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not tell the native hook which script events to send", e);
        }
    }

    /**
     * Posts the event for one report, if it has subscribers, with the client's stack getters giving the stacks the
     * hook captured while its handlers run.
     *
     * @param run the report
     */
    void post(ScriptRun run) {
        Object event;
        if (run.kind() == ScriptRun.Kind.POST) {
            if (!bus.hasSubscribers(ScriptPostFired.class)) {
                return;
            }
            event = new ScriptPostFired(run.scriptId());
        } else {
            if (!bus.hasSubscribers(ScriptPreFired.class)) {
                return;
            }
            ScriptPreFired pre = new ScriptPreFired(run.scriptId());
            if (run.kind() == ScriptRun.Kind.PRE_ROOT) {
                ScriptRequest request = run.request() != null ? run.request() : ScriptRequest.of(run.scriptId());
                pre.setScriptEvent(new ScriptEvent(client, request));
            }
            event = pre;
        }
        client.setScriptStack(run.stack());
        try {
            bus.post(event);
        } finally {
            client.setScriptStack(null);
        }
    }
}
