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

    /**
     * The item with a key.
     *
     * @param key the item's key
     * @return the item, or null if there is none
     */
    public ConfigItemDescriptor item(String key) {
        for (ConfigItemDescriptor item : items) {
            if (item.getKey().equals(key)) {
                return item;
            }
        }
        return null;
    }

    /**
     * The section with a key.
     *
     * @param key the section's key
     * @return the section, or null if there is none
     */
    public ConfigSectionDescriptor section(String key) {
        for (ConfigSectionDescriptor section : sections) {
            if (section.getKey().equals(key)) {
                return section;
            }
        }
        return null;
    }
}
