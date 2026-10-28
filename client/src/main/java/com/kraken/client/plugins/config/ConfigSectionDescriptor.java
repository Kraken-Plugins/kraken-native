package com.kraken.client.plugins.config;

import lombok.Value;

import java.util.List;

/**
 * One section of a {@link ConfigDescriptor}.
 */
@Value
public class ConfigSectionDescriptor implements ConfigObject {

    /**
     * The section's key: the value of the field it annotates, which items name in {@link ConfigItem#section()}.
     */
    String key;

    /**
     * The section's annotation.
     */
    ConfigSection section;

    /**
     * The conditions that must all hold for the settings page to show it; empty if it is always shown.
     */
    List<ShowIf> showIf;

    @Override
    public String getName() {
        return section.name();
    }

    @Override
    public int getPosition() {
        return section.position();
    }
}
