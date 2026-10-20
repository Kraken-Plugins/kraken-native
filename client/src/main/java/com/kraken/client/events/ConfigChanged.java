package com.kraken.client.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A setting was set or unset, as RuneLite's {@code ConfigChanged}. Posted on the thread that made the change, usually
 * the EDT (the settings page); a plugin that needs the game in response hands the work to {@code ClientThread}.
 *
 * <p>Plugins' on/off switches are settings too (group {@code kraken}, see {@code PluginManager}), so turning a plugin
 * on or off posts one, as in RuneLite.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigChanged {

    /**
     * The setting's group, a {@code @ConfigGroup} value.
     */
    private String group;

    /**
     * The profile the setting belongs to. Always null: Kraken has no profiles. Kept so RuneLite code compiles.
     */
    private String profile;

    /**
     * The setting's key within the group.
     */
    private String key;

    /**
     * The value before, or null if it wasn't set.
     */
    private String oldValue;

    /**
     * The value now, or null if it was unset.
     */
    private String newValue;
}
