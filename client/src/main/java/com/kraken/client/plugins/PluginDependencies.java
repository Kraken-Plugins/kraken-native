package com.kraken.client.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds repeated {@link PluginDependency} annotations. Write {@code @PluginDependency} several times instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginDependencies {

    /**
     * The dependencies.
     *
     * @return one annotation per plugin depended on
     */
    PluginDependency[] value();
}
