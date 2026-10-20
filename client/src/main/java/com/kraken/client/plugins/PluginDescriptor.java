package com.kraken.client.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a {@link Plugin}, as RuneLite's {@code @PluginDescriptor}. A class with it under
 * {@code com.kraken.client.plugins} is found and loaded at launch.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginDescriptor {

    /**
     * The plugin's name, shown in the Plugins panel.
     *
     * @return the name
     */
    String name();

    /**
     * A one-line summary, shown as the Plugins panel's tooltip.
     *
     * @return the description
     */
    String description() default "";

    /**
     * Keywords the Plugins panel's search matches, lowercase and without spaces.
     *
     * @return the tags
     */
    String[] tags() default {};

    /**
     * Names that can't be on at the same time as this plugin. Two plugins conflict when one's name or conflicts
     * matches the other's; starting one switches off the plugins it conflicts with.
     *
     * @return the conflicting names
     */
    String[] conflicts() default {};

    /**
     * Whether the plugin is on until the user switches it off.
     *
     * @return true to start it by default
     */
    boolean enabledByDefault() default true;

    /**
     * Whether to leave the plugin out of the Plugins panel. It still loads and starts as configured.
     *
     * @return true to hide it
     */
    boolean hidden() default false;

    /**
     * Whether the plugin is for developers: only loaded with {@code --developer-mode}.
     *
     * @return true for a developer plugin
     */
    boolean developerPlugin() default false;
}
