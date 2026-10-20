package com.kraken.client.plugins;

import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Module;

/**
 * A Kraken plugin. Annotate the subclass with {@link PluginDescriptor}; the {@link PluginManager} builds it with a
 * no-argument constructor, injects its {@code @Inject} fields from a child of the client's injector, and installs it as
 * a Guice module, so its {@code @Provides} methods work.
 *
 * <p>Lifecycle: {@link #startUp()} runs on the EDT, then the plugin's {@code @Subscribe} methods are
 * registered on the event bus. Stopping unregisters them, then runs {@link #shutDown()} on the EDT. Game events and
 * {@code ClientThread} tasks run on the client thread, the API's poll thread.
 */
public abstract class Plugin implements Module {

    /**
     * The plugin's own injector, set by the {@link PluginManager} when it loads the plugin.
     */
    protected Injector injector;

    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }

    /**
     * Adds bindings to the plugin's injector. The plugin's {@code @Provides} methods are picked up without this.
     *
     * @param binder the plugin injector's binder
     */
    @Override
    public void configure(Binder binder) {
    }

    /**
     * Starts the plugin, on the EDT. Throwing marks the plugin failed and switches it off.
     *
     * @throws Exception if the plugin can't start
     */
    protected void startUp() throws Exception {
    }

    /**
     * Stops the plugin, on the EDT. Also runs after a {@link #startUp()} that threw.
     *
     * @throws Exception if the plugin can't stop cleanly; it's logged and the plugin counts as stopped
     */
    protected void shutDown() throws Exception {
    }

    /**
     * The services this plugin offers the plugins that declare a {@link PluginDependency} on it: the module is
     * installed into each dependent's injector.
     *
     * @return the module, or null if the plugin offers none (then no plugin can depend on it)
     */
    protected Module getPublicModule() {
        return null;
    }

    /**
     * The plugin's injector, a child of the client's.
     *
     * @return the injector, or null if the plugin couldn't be loaded
     */
    public final Injector getInjector() {
        return injector;
    }

    /**
     * The plugin's name, from its {@link PluginDescriptor}.
     *
     * @return the name shown in the Plugins panel
     */
    public String getName() {
        return getClass().getAnnotation(PluginDescriptor.class).name();
    }
}
