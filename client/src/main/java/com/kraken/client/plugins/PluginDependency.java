package com.kraken.client.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says a {@link Plugin} uses another one's services. The other plugin is loaded first (and started first when both are
 * on), and its {@link Plugin#getPublicModule() public module} is installed into this plugin's injector, so it must
 * offer one. A plugin whose dependency can't be loaded isn't loaded either. Repeat the annotation for several
 * dependencies.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(PluginDependencies.class)
public @interface PluginDependency {

    /**
     * The plugin depended on.
     *
     * @return its class
     */
    Class<? extends Plugin> value();
}
