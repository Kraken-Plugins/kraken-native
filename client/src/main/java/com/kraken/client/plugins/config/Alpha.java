package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets the settings page's colour picker choose transparency for a {@code Color} {@link ConfigItem}, as RuneLite's
 * {@code @Alpha}. Without it the picker keeps colours opaque.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Alpha {
}
