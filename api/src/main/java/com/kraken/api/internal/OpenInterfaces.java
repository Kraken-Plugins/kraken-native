package com.kraken.api.internal;

import com.kraken.api.widgets.WidgetNode;

import java.util.Map;

/**
 * Internal. The interfaces open at one read of the client, which the interface events compare poll to poll.
 *
 * @param topLevel   the top-level interface's group, -1 while there is none
 * @param components each group opened in a component, by the component's packed id
 */
public record OpenInterfaces(int topLevel, Map<Integer, WidgetNode> components) {
}
