package com.kraken.client.events;

import com.kraken.client.plugins.Plugin;
import lombok.Data;

/**
 * A plugin started or stopped. Posted on the EDT, after the plugin's
 * {@code startUp} (and the registration of its subscribers) or after its {@code shutDown}.
 */
@Data
public class PluginChanged {

    /**
     * The plugin.
     */
    private final Plugin plugin;

    /**
     * True if it started, false if it stopped.
     */
    private final boolean loaded;
}
