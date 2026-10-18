package com.kraken.client.devtools;

import lombok.Getter;

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
    private volatile boolean on;

    ToolOption(String key, String label, boolean defaultValue) {
        this.key = key;
        this.label = label;
        this.defaultValue = defaultValue;
        this.on = defaultValue;
    }

    /** Safe to call from any thread. */
    public boolean isOn() {
        return on;
    }

    void set(boolean on) {
        this.on = on;
    }
}
