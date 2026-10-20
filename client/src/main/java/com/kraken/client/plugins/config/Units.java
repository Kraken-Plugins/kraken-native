package com.kraken.client.plugins.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The unit shown after a numeric {@link ConfigItem}'s value on the settings page. Use one of the constants, or any
 * suffix.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Units {

    /**
     * Milliseconds.
     */
    String MILLISECONDS = "ms";

    /**
     * Minutes.
     */
    String MINUTES = " mins";

    /**
     * A percentage.
     */
    String PERCENT = "%";

    /**
     * Pixels.
     */
    String PIXELS = "px";

    /**
     * Points.
     */
    String POINTS = "pt";

    /**
     * Seconds.
     */
    String SECONDS = "s";

    /**
     * Game ticks (0.6 s each).
     */
    String TICKS = " ticks";

    /**
     * Levels.
     */
    String LEVELS = " lvls";

    /**
     * Frames per second.
     */
    String FPS = " fps";

    /**
     * Gold pieces.
     */
    String GP = " GP";

    /**
     * The suffix.
     *
     * @return the text shown after the value
     */
    String value();
}
