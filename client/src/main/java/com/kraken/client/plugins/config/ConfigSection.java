package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A collapsible group of settings on the settings page. It goes on a {@code String} field of the {@link Config}
 * interface; the field's value is the key items name in {@link ConfigItem#section()}:
 *
 * <pre>{@code
 * @ConfigSection(name = "Display", description = "How highlights look", position = 1)
 * String display = "display";
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigSection {

    /**
     * The section's title.
     *
     * @return the name
     */
    String name();

    /**
     * A longer explanation, shown as the title's tooltip.
     *
     * @return the description
     */
    String description();

    /**
     * Where the section goes among the page's items and sections, lowest first.
     *
     * @return the position
     */
    int position();

    /**
     * Whether the section starts collapsed.
     *
     * @return true to start collapsed
     */
    boolean closedByDefault() default false;
}
