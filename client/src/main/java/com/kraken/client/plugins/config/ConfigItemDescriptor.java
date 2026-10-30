package com.kraken.client.plugins.config;

import lombok.Value;

import java.lang.reflect.Type;
import java.util.List;

/**
 * One setting of a {@link ConfigDescriptor}: its annotation, its value type, the annotations that shape its control
 * and the conditions under which it is shown.
 */
@Value
public class ConfigItemDescriptor implements ConfigObject {

    /**
     * The item's annotation.
     */
    ConfigItem item;

    /**
     * The getter's generic return type, e.g. {@code int} or {@code Set<Skill>}.
     */
    Type type;

    /**
     * The value's bounds, or null.
     */
    Range range;

    /**
     * Present if a colour may be transparent, or null.
     */
    Alpha alpha;

    /**
     * The unit shown after a number, or null.
     */
    Units units;

    /**
     * Present if the setting has a drop-down of suggestions, or null.
     */
    Suggestions suggestions;

    /**
     * The conditions that must all hold for the settings page to show it; empty if it is always shown.
     */
    List<ShowIf> showIf;

    /**
     * Present if the client can't act on the setting yet, or null.
     */
    Unsupported unsupported;

    @Override
    public String getKey() {
        return item.keyName();
    }

    @Override
    public String getName() {
        return item.name();
    }

    @Override
    public int getPosition() {
        return item.position();
    }
}
