package com.kraken.api.internal.event;

import com.kraken.api.internal.ScriptComponents;
import com.kraken.api.internal.ScriptStack;

import java.util.Objects;

/**
 * One script event the native hook reported: a script starting or returning, with the top of the stacks and the
 * components the engine's ops act on as they were at that moment and, for a root run a component's listener started,
 * the listener's request. Internal: the client's hook server builds these off the socket and hands them to
 * {@code GameSession.onScript}; plugins see {@code ScriptPreFired} and {@code ScriptPostFired}.
 *
 * @param kind       what happened
 * @param scriptId   the script's id (for a script a trigger started, its trigger key)
 * @param depth      the script's depth: 0 for a root script, one more for each script that called it
 * @param stack      the stacks as the hook captured them
 * @param request    the listener's request for a root run a listener started, else null
 * @param components the active and dot components as the hook captured them
 */
public record ScriptRun(Kind kind, int scriptId, int depth, ScriptStack stack, ScriptRequest request,
                        ScriptComponents components) {

    /**
     * Fills in no components for a report that carries none.
     *
     * @param kind       what happened
     * @param scriptId   the script's id
     * @param depth      the script's depth
     * @param stack      the stacks
     * @param request    the listener's request, or null
     * @param components the components, or null for none
     */
    public ScriptRun {
        components = Objects.requireNonNullElse(components, ScriptComponents.NONE);
    }

    /**
     * A report without the components.
     *
     * @param kind     what happened
     * @param scriptId the script's id
     * @param depth    the script's depth
     * @param stack    the stacks
     * @param request  the listener's request, or null
     */
    public ScriptRun(Kind kind, int scriptId, int depth, ScriptStack stack, ScriptRequest request) {
        this(kind, scriptId, depth, stack, request, ScriptComponents.NONE);
    }

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
