package com.kraken.client.plugins.config;

import com.kraken.client.events.ConfigButtonClicked;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The type of a {@link ConfigItem} that is a button: the settings page shows a button with the item's name, and
 * clicking it posts {@link ConfigButtonClicked} with the item's group and key (after asking, if the item has a
 * {@link ConfigItem#warning()}). Nothing is stored. Declare the getter without a body; it returns {@link #INSTANCE}:
 *
 * <pre>{@code
 * @ConfigItem(keyName = "run", name = "Run", description = "Runs the test once")
 * Button run();
 * }</pre>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Button {

    /**
     * What every button getter returns.
     */
    public static final Button INSTANCE = new Button();

    @Override
    public String toString() {
        return "Button";
    }
}
