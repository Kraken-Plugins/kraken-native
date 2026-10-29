package com.kraken.api.internal.event;

/**
 * Tells the native hook which script events to send. Internal: the client's hook server hands one to
 * {@code GameSession.setScriptHook} while a hook that reports scripts is connected; the API switches the events on
 * while {@code ScriptPreFired} or {@code ScriptPostFired} has subscribers, since every script the client runs costs a
 * message, and asks for single root scripts that other events come from ({@code FakeXpDrop}).
 */
@FunctionalInterface
public interface ScriptHook {

    /**
     * Sets which script events the hook sends.
     *
     * @param pre   whether to send scripts starting (root runs and gosubs)
     * @param post  whether to send scripts returning
     * @param roots root scripts to send as they start even when {@code pre} is off, by id; empty for none
     */
    void capture(boolean pre, boolean post, int[] roots);
}
