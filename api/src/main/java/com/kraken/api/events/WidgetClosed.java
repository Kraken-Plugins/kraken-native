package com.kraken.api.events;

import com.kraken.api.widgets.WidgetModalMode;
import lombok.Value;

/**
 * An interface (widget group) opened in a component closed: the player closed it, the server closed or replaced it, or
 * the client closed every interface at logout. The top-level interface posts none when another replaces it.
 *
 * <p>Posted after the fact: the client has already closed the interface, so its widgets may be gone (see
 * {@link #isUnload()}).
 */
@Value
public class WidgetClosed {

    /**
     * The interface that closed, e.g. {@code InterfaceID.BANKMAIN}.
     */
    int groupId;

    /**
     * How it was opened, one of {@link WidgetModalMode}'s modes; -1 if this build's mapping can't say.
     */
    int modalMode;

    /**
     * Whether the client dropped the interface's widgets as it closed it. False when the same interface is opened
     * again in the same component right after, which is only seen while the native hook is connected; a close the poll
     * notices is always an unload.
     */
    boolean unload;
}
