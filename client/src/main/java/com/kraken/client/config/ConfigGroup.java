package com.kraken.client.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the group a {@link Config} interface's settings are stored under, as RuneLite's {@code @ConfigGroup}. The
 * settings file keys them {@code group.key}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigGroup {

    /**
     * The group's name, usually the plugin's name in lower case without spaces.
     *
     * @return the group
     */
    String value();
}
