package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.ScriptEvent;
import com.kraken.api.Skill;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.FakeXpDrop;
import com.kraken.api.events.ScriptPostFired;
import com.kraken.api.events.ScriptPreFired;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts {@link ScriptPreFired} and {@link ScriptPostFired} for the script events the native hook reports, and
 * {@link FakeXpDrop} for the XP drop script's runs, and keeps the hook reporting only what has subscribers. Unlike the
 * trackers it reads nothing to find its events: each report is one event, posted with the stacks it captured. Poll
 * thread only.
 */
final class ScriptEvents {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The script the server runs to show an XP drop that adds no XP, with the skill and the XP as its arguments.
     */
    static final int XP_DROP_SCRIPT = 2091;

    private static final int[] NO_ROOTS = {};
    private static final int[] XP_DROP_ROOTS = {XP_DROP_SCRIPT};
    private static final Skill[] SKILLS = Skill.values();

    private final Client client;
    private final EventBus bus;
    private ScriptHook hook;

    /**
     * What the hook was last told to send: bit 0 starts, bit 1 returns, bit 2 the XP drop script's runs; -1 when it
     * hasn't been told since it connected.
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
        boolean xpDrops = bus.hasSubscribers(FakeXpDrop.class);
        int wanted = (pre ? 1 : 0) | (post ? 2 : 0) | (xpDrops ? 4 : 0);
        if (wanted == sent) {
            return;
        }
        try {
            hook.capture(pre, post, xpDrops ? XP_DROP_ROOTS : NO_ROOTS);
            sent = wanted;
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not tell the native hook which script events to send", e);
        }
    }

    /**
     * Posts the events for one report, if they have subscribers, with the client's stack getters giving the stacks the
     * hook captured while their handlers run: the script's {@link ScriptPreFired} or {@link ScriptPostFired}, then a
     * {@link FakeXpDrop} if it is the XP drop script starting.
     *
     * @param run the report
     */
    void post(ScriptRun run) {
        Object event = null;
        if (run.kind() == ScriptRun.Kind.POST) {
            if (bus.hasSubscribers(ScriptPostFired.class)) {
                event = new ScriptPostFired(run.scriptId());
            }
        } else if (bus.hasSubscribers(ScriptPreFired.class)) {
            ScriptPreFired pre = new ScriptPreFired(run.scriptId());
            if (run.kind() == ScriptRun.Kind.PRE_ROOT) {
                ScriptRequest request = run.request() != null ? run.request() : ScriptRequest.of(run.scriptId());
                pre.setScriptEvent(new ScriptEvent(client, request));
            }
            event = pre;
        }
        FakeXpDrop drop = xpDrop(run);
        if (event == null && drop == null) {
            return;
        }
        client.setScriptStack(run.stack());
        try {
            if (event != null) {
                bus.post(event);
            }
            if (drop != null) {
                bus.post(drop);
            }
        } finally {
            client.setScriptStack(null);
        }
    }

    /**
     * The XP drop a report shows, if it is the XP drop script starting as a root run (the server runs it) and
     * {@link FakeXpDrop} has subscribers.
     *
     * @param run the report
     * @return the drop, or null if the report isn't one or its arguments aren't a skill and an XP amount
     */
    private FakeXpDrop xpDrop(ScriptRun run) {
        if (run.kind() != ScriptRun.Kind.PRE_ROOT || run.scriptId() != XP_DROP_SCRIPT || run.request() == null
                || !bus.hasSubscribers(FakeXpDrop.class)) {
            return null;
        }
        Object[] arguments = run.request().arguments();
        if (arguments.length < 3 || !(arguments[1] instanceof Integer skill) || !(arguments[2] instanceof Integer xp)
                || skill < 0 || skill >= SKILLS.length) {
            return null;
        }
        return new FakeXpDrop(SKILLS[skill], xp);
    }
}
