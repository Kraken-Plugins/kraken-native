package com.kraken.client.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A settings page's button was clicked: a {@code Button} item of a plugin's config. Posted on the EDT; a plugin that
 * needs the game in response hands the work to {@code ClientThread}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigButtonClicked {

    /**
     * The button's group, a {@code @ConfigGroup} value.
     */
    private String group;

    /**
     * The button item's key within the group.
     */
    private String key;
}
