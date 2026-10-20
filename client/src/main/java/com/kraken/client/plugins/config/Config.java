package com.kraken.client.plugins.config;

/**
 * A plugin's settings: an interface extending this, annotated {@link ConfigGroup}, with one {@link ConfigItem} method
 * per setting whose {@code default} body gives the setting's default. {@link ConfigManager#getConfig} implements it;
 * the Plugins panel builds the plugin's settings page from it.
 *
 * <pre>{@code
 * @ConfigGroup("example")
 * public interface ExampleConfig extends Config {
 *     @ConfigItem(keyName = "greeting", name = "Greeting", description = "What to say")
 *     default String greeting() {
 *         return "Hello";
 *     }
 * }
 * }</pre>
 */
public interface Config {
}
