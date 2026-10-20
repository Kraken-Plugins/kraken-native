package com.kraken.client.plugins.config;

/**
 * Something on a settings page, an item or a section: what the page orders them by.
 */
public interface ConfigObject {

    /**
     * The item's key, or the section's.
     *
     * @return the key
     */
    String getKey();

    /**
     * The label or title.
     *
     * @return the name
     */
    String getName();

    /**
     * Where it goes on the page, lowest first.
     *
     * @return the position
     */
    int getPosition();
}
