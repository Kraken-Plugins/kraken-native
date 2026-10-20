package com.kraken.client.plugins.config;

import lombok.Value;

import java.lang.reflect.Type;

/**
 * One setting of a {@link ConfigDescriptor}: its annotation, its value type and the annotations that shape its
 * control.
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
