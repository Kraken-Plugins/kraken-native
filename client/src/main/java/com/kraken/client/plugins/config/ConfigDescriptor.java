package com.kraken.client.plugins.config;

import lombok.Value;

import java.util.List;

/**
 * What a {@link Config} interface declares: its group, sections and items, each list in page order (position, then
 * name). {@link ConfigManager#getConfigDescriptor} builds it; the settings page is made from it.
 */
@Value
public class ConfigDescriptor {

    /**
     * The interface's group.
     */
    ConfigGroup group;

    /**
     * Its sections.
     */
    List<ConfigSectionDescriptor> sections;

    /**
     * Its items (the getters), hidden ones included.
     */
    List<ConfigItemDescriptor> items;
}
