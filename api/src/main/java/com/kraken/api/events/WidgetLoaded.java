package com.kraken.api.events;

import lombok.Data;

/**
 * An interface (widget group) opened: in a component, or as the top-level interface. Posted after the client opened
 * it and holds its widgets, so a handler can read them with {@code Client.getWidget}.
 *
 * <p>A group the client closes and opens again in the same component (a dialogue going on to its next line) posts a
 * {@link WidgetClosed} and a {@code WidgetLoaded} each time while the native hook is connected; without the hook only
 * the changes a poll can see post, so such a reopen posts nothing.
 */
@Data
public class WidgetLoaded {

    /**
     * The interface that opened, e.g. {@code InterfaceID.BANKMAIN}.
     */
    private int groupId;
}
