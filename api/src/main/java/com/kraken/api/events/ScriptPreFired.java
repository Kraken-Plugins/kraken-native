package com.kraken.api.events;

import com.kraken.api.ScriptEvent;
import lombok.Data;

/**
 * A script is about to run: a root script the client starts (from a component's listener, or a trigger), or a script
 * another calls (a gosub), before the callee is looked up, with its arguments on top of the stacks.
 *
 * <p>The native hook reports each one as it happens, with the top of the stacks, and the API posts it on the client
 * thread a moment later, so the game has moved on: what a handler reads from the game is as it is now, after the
 * script ran. While the handler runs, {@code Client.getIntStack()}, {@code getIntStackSize()},
 * {@code getObjectStack()} and {@code getObjectStackSize()} give the stacks as they were at the event. Changing them,
 * or the event's arguments, doesn't change the game.
 *
 * <p>Needs the native hook, which only reports scripts while this event or {@link ScriptPostFired} has subscribers;
 * without it nothing is posted. A busy interface runs many scripts a frame, so keep handlers short.
 */
@Data
public class ScriptPreFired {

    /**
     * The script's id. A script a trigger started has its trigger key instead.
     */
    private final int scriptId;

    /**
     * What the root script ran with; null for a script another called.
     */
    private ScriptEvent scriptEvent;
}
