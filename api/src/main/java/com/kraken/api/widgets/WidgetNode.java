package com.kraken.api.widgets;

import com.kraken.api.Client;
import lombok.Value;

/**
 * An interface opened inside a component, as the client's component table holds it: the inventory in its side panel,
 * say. Get them from {@link Client#getComponentTable()}, keyed by the component they are opened in.
 */
@Value
public class WidgetNode {

    /**
     * The interface (group) opened in the component.
     */
    int id;
}
