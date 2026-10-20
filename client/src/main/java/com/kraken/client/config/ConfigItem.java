package com.kraken.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One setting of a {@link Config} interface, as RuneLite's {@code @ConfigItem}. On a method with no parameters it is
 * the getter: it returns the stored value, or the method's {@code default} body when nothing is stored. On a
 * {@code void} method with one parameter it is a setter for the same key.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigItem {

    /**
     * Where the setting goes on the settings page, lowest first; ties are ordered by name.
     *
     * @return the position
     */
    int position() default -1;

    /**
     * The key the value is stored under, within the group.
     *
     * @return the key
     */
    String keyName();

    /**
     * The setting's label on the settings page.
     *
     * @return the name
     */
    String name();

    /**
     * A longer explanation, shown as the label's tooltip.
     *
     * @return the description
     */
    String description();

    /**
     * Whether to leave the setting off the settings page. Plugins use hidden items to keep their own state.
     *
     * @return true to hide it
     */
    boolean hidden() default false;

    /**
     * A warning the settings page shows before changing the value; the change only happens if the user confirms.
     *
     * @return the warning, or "" for none
     */
    String warning() default "";

    /**
     * Whether the value is masked on the settings page (a password or a key).
     *
     * @return true to mask it
     */
    boolean secret() default false;

    /**
     * The {@link ConfigSection} the setting is shown under: the value of the section's field.
     *
     * @return the section's key, or "" for none
     */
    String section() default "";
}
