package com.kraken.api.eventbus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that {@link EventBus#register} subscribes to the event type it takes. The method takes exactly one
 * parameter (the event), returns {@code void}, isn't static, and can have any visibility:
 *
 * <pre>{@code
 * @Subscribe
 * public void onNpcSpawned(NpcSpawned event) {
 *     ...
 * }
 * }</pre>
 *
 * <p>The parameter can also be a supertype or an interface the event implements; the method then receives every event
 * of that type.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /**
     * Priority relative to the event's other subscribers. Higher priorities run first; equal priorities run in the
     * order they were registered.
     *
     * @return the priority, 0 by default
     */
    float priority() default 0;
}
