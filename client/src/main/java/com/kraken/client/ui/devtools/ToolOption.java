package com.kraken.client.ui.devtools;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * An on/off setting for one tool, such as which fields its overlay shows. Listed under the tool in the sidebar and
 * saved between sessions. Change it through {@link DevToolManager#setOption} so it's saved and the overlay repaints.
 */
public final class ToolOption {
    @Getter
    private final String key;
    @Getter
    private final String label;
    @Getter
    private final boolean defaultValue;

    /**
     * Whether the option is on. Safe to read from any thread.
     */
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private volatile boolean on;

    ToolOption(String key, String label, boolean defaultValue) {
        this.key = key;
        this.label = label;
        this.defaultValue = defaultValue;
        this.on = defaultValue;
    }
}
