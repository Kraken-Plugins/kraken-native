package com.kraken.client.plugins.config;

import com.kraken.client.events.ConfigSuggestionsRequested;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a {@code String} {@link ConfigItem} a drop-down of suggestions the plugin supplies, such as the names of what
 * is loaded in the game. The field still takes any text. Each time the drop-down opens, the settings page posts
 * {@link ConfigSuggestionsRequested} for the item, and the list shows what the plugin answers:
 *
 * <pre>{@code
 * @Subscribe
 * public void onConfigSuggestionsRequested(ConfigSuggestionsRequested event) {
 *     if (MyConfig.GROUP.equals(event.getGroup()) && "npc".equals(event.getKey())) {
 *         clientThread.invoke(() -> event.suggest(nearbyNpcNames()));
 *     }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Suggestions {
}
