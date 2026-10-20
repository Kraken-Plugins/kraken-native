package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The bounds of a numeric {@link ConfigItem}. The settings page's spinner stays within them; values set through the
 * config interface aren't checked.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Range {

    /**
     * The lowest value.
     *
     * @return the minimum
     */
    int min() default 0;

    /**
     * The highest value.
     *
     * @return the maximum
     */
    int max() default Integer.MAX_VALUE;
}
