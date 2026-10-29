package com.kraken.api.events;

import lombok.Value;

/**
 * A script returned, its return values on top of the stacks: a script another called, or a root script. A root script
 * that stops on an error is posted too, as it ends.
 *
 * <p>Posted on the client thread a moment after the script returned, so what a handler reads from the game (the
 * components the script built, say) is as it is now. While the handler runs, {@code Client.getIntStack()} and the
 * other stack getters give the stacks as they were at the return. Needs the native hook, as {@link ScriptPreFired}
 * does.
 */
@Value
public class ScriptPostFired {

    /**
     * The script's id. A script a trigger started has its trigger key instead.
     */
    int scriptId;
}
