package com.kraken.api.internal.event;

import com.kraken.api.internal.ScriptStack;

/**
 * One script event the native hook reported: a script starting or returning, with the top of the stacks as they were
 * at that moment and, for a root run a component's listener started, the listener's request. Internal: the client's
 * hook server builds these off the socket and hands them to {@code GameSession.onScript}; plugins see
 * {@code ScriptPreFired} and {@code ScriptPostFired}.
 *
 * @param kind     what happened
 * @param scriptId the script's id (for a script a trigger started, its trigger key)
 * @param depth    the script's depth: 0 for a root script, one more for each script that called it
 * @param stack    the stacks as the hook captured them
 * @param request  the listener's request for a root run a listener started, else null
 */
public record ScriptRun(Kind kind, int scriptId, int depth, ScriptStack stack, ScriptRequest request) {

    /**
     * What the client's script engine did.
     */
    public enum Kind {

        /**
         * A root script started: the client ran it from a component's listener or a trigger.
         */
        PRE_ROOT,

        /**
         * A script called another (a gosub), whose arguments are on top of the stacks.
         */
        PRE_GOSUB,

        /**
         * A script returned, its return values on top of the stacks. A root run that stops on an error ends this way
         * too.
         */
        POST
    }
}
