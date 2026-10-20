package com.kraken.client.plugins;

import com.google.inject.Binding;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.KrakenConfig;
import com.kraken.client.events.PluginChanged;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigManager;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import lombok.extern.java.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;

/**
 * Loads, starts and stops {@link Plugin}s, as RuneLite's {@code PluginManager}.
 *
 * <ul>
 *   <li><b>Discovery.</b> {@link #loadCorePlugins} finds every top-level {@link PluginDescriptor} class under
 *       {@code com.kraken.client.plugins}; {@link #loadPlugins} takes classes from anywhere (the developer launcher's
 *       {@code --plugin}). Developer plugins only load with {@code --developer-mode}.
 *   <li><b>Injection.</b> Each plugin gets a child of the client's injector, with the plugin bound and installed as a
 *       module, plus the {@link Plugin#getPublicModule() public module} of each plugin it depends on.
 *   <li><b>Order.</b> Plugins load and start in dependency order and stop in reverse.
 *   <li><b>Lifecycle.</b> Starting runs {@code startUp} on the EDT, registers the plugin on the event bus and posts
 *       {@link PluginChanged}; stopping unregisters it, runs {@code shutDown} on the EDT and posts {@link PluginChanged}.
 *       A plugin whose {@code startUp} throws is logged, marked failed ({@link #getFailure}) and switched off.
 *   <li><b>Enabled state</b> is kept in config group {@value #CONFIG_GROUP}, under the plugin class's simple name
 *       lower-cased, as RuneLite keeps it under {@code runelite}.
 *   <li><b>Conflicts.</b> Turning a plugin on turns off the plugins it {@linkplain #conflictsForPlugin conflicts} with.
 * </ul>
 *
 * <p>Start and stop calls may come from any thread; the work runs on the EDT and the call waits for it.
 */
@Log(topic = "kraken")
@Singleton
public class PluginManager {

    /**
     * The config group plugins' on/off state is kept in.
     */
    public static final String CONFIG_GROUP = KrakenConfig.GROUP;

    private static final String PLUGIN_PACKAGE = "com.kraken.client.plugins";

    private final Injector parent;
    private final EventBus eventBus;
    private final ConfigManager configManager;
    private final Executor edt;
    private final boolean developerMode;
    private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
    private final Set<Plugin> active = ConcurrentHashMap.newKeySet();
    private final Map<Plugin, Throwable> failures = new ConcurrentHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates the manager.
     *
     * @param parent        the client's injector, parent of every plugin's
     * @param eventBus      the bus plugins are registered on
     * @param configManager keeps the plugins' on/off state
     * @param edt           runs plugin start-up and shut-down (the EDT in the client)
     * @param developerMode whether developer plugins load
     */
    @Inject
    public PluginManager(Injector parent, EventBus eventBus, ConfigManager configManager, @Named("edt") Executor edt,
                         @Named("developerMode") boolean developerMode) {
        this.parent = parent;
        this.eventBus = eventBus;
        this.configManager = configManager;
        this.edt = edt;
        this.developerMode = developerMode;
    }

    /**
     * Finds and loads the built-in plugins: every top-level class under {@code com.kraken.client.plugins} with a
     * {@link PluginDescriptor}.
     *
     * @return the plugins loaded
     */
    public List<Plugin> loadCorePlugins() {
        List<Class<?>> classes = new ArrayList<>();
        try (ScanResult scan = new ClassGraph().enableAnnotationInfo().acceptPackages(PLUGIN_PACKAGE).scan()) {
            for (ClassInfo info : scan.getClassesWithAnnotation(PluginDescriptor.class.getName())) {
                if (!info.isInnerClass()) {
                    classes.add(info.loadClass());
                }
            }
        }
        return loadPlugins(classes);
    }

    /**
     * Loads plugin classes: creates each, in dependency order, and gives it its injector. A class already loaded is
     * skipped; one that can't be created, or whose dependency isn't available, is logged and skipped; one whose
     * injector can't be built is listed as failed.
     *
     * @param classes {@link Plugin} subclasses with a {@link PluginDescriptor}
     * @return the plugins loaded
     */
    public List<Plugin> loadPlugins(Collection<? extends Class<?>> classes) {
        Map<Class<?>, Plugin> loaded = new HashMap<>();
        plugins.forEach(plugin -> loaded.put(plugin.getClass(), plugin));
        Set<Class<? extends Plugin>> candidates = new LinkedHashSet<>();
        for (Class<?> clazz : classes) {
            PluginDescriptor descriptor = clazz.getAnnotation(PluginDescriptor.class);
            if (!Plugin.class.isAssignableFrom(clazz) || descriptor == null) {
                log.warning(clazz.getName() + " isn't a plugin: it needs to extend Plugin and have @PluginDescriptor");
                continue;
            }
            if (Modifier.isAbstract(clazz.getModifiers()) || descriptor.developerPlugin() && !developerMode
                    || loaded.containsKey(clazz)) {
                continue;
            }
            candidates.add(clazz.asSubclass(Plugin.class));
        }
        List<Plugin> added = new ArrayList<>();
        for (Class<? extends Plugin> clazz : dependencyOrder(candidates, loaded.keySet())) {
            Plugin plugin = instantiate(clazz, loaded);
            if (plugin != null) {
                loaded.put(clazz, plugin);
                plugins.add(plugin);
                added.add(plugin);
            }
        }
        changed();
        return added;
    }

    /**
     * Starts every plugin that is on, in dependency order. The client calls this once its plugins are loaded.
     */
    public void startPlugins() {
        for (Plugin plugin : plugins) {
            if (isPluginEnabled(plugin) && !isPluginActive(plugin)) {
                startPlugin(plugin);
            }
        }
    }

    /**
     * Stops every running plugin, in reverse dependency order, without changing whether they're on. The client calls
     * this as it exits.
     */
    public void stopPlugins() {
        List<Plugin> reversed = new ArrayList<>(plugins);
        Collections.reverse(reversed);
        for (Plugin plugin : reversed) {
            stopPlugin(plugin);
        }
    }

    /**
     * Starts a plugin that is on and not running, on the EDT. Plugins it conflicts with are turned off first. If its
     * {@code startUp} throws, it's stopped again, marked failed and turned off.
     *
     * @param plugin a loaded plugin
     * @return true if it started; false if it was already running, is off, couldn't be loaded, or failed
     */
    public boolean startPlugin(Plugin plugin) {
        return onEdt(() -> start(plugin));
    }

    /**
     * Stops a running plugin, on the EDT. It stays on (see {@link #setPluginEnabled}).
     *
     * @param plugin a loaded plugin
     * @return true if it was running
     */
    public boolean stopPlugin(Plugin plugin) {
        return onEdt(() -> stop(plugin));
    }

    /**
     * Turns a plugin on or off for this and later launches; turning one on turns off the plugins it conflicts with.
     * It doesn't start or stop anything: follow it with {@link #startPlugin} or {@link #stopPlugin}.
     *
     * @param plugin  a loaded plugin
     * @param enabled whether it should be on
     */
    public void setPluginEnabled(Plugin plugin, boolean enabled) {
        configManager.setConfiguration(CONFIG_GROUP, configKey(plugin), String.valueOf(enabled));
        if (enabled) {
            for (Plugin conflict : conflictsForPlugin(plugin)) {
                if (isPluginEnabled(conflict)) {
                    setPluginEnabled(conflict, false);
                }
            }
        }
        changed();
    }

    /**
     * Whether a plugin is on, so starts with the client.
     *
     * @param plugin a loaded plugin
     * @return the stored state, or the descriptor's {@code enabledByDefault} if there is none
     */
    public boolean isPluginEnabled(Plugin plugin) {
        String value = configManager.getConfiguration(CONFIG_GROUP, configKey(plugin));
        return value != null ? Boolean.parseBoolean(value) : descriptor(plugin).enabledByDefault();
    }

    /**
     * Whether a plugin is running: on and started without failing.
     *
     * @param plugin a loaded plugin
     * @return true while it runs
     */
    public boolean isPluginActive(Plugin plugin) {
        return active.contains(plugin);
    }

    /**
     * Why a plugin last failed to load or start. Kraken extra; the Plugins panel shows it.
     *
     * @param plugin a loaded plugin
     * @return the error, or null if it hasn't failed since it last started
     */
    public Throwable getFailure(Plugin plugin) {
        return failures.get(plugin);
    }

    /**
     * The plugin's settings, as RuneLite's {@code getPluginConfigProxy}: the {@link Config} its injector provides,
     * usually through a {@code @Provides} method calling {@code ConfigManager.getConfig}. A config that a dependency's
     * public module provides belongs to the dependency, so it doesn't count. The Plugins panel shows a settings button
     * for plugins that have one.
     *
     * @param plugin a loaded plugin
     * @return the config, or null if the plugin provides none, couldn't be loaded, or its config can't be created
     *         (logged)
     */
    public Config getPluginConfigProxy(Plugin plugin) {
        Injector injector = plugin.getInjector();
        if (injector == null) {
            return null;
        }
        Set<Key<?>> fromDependencies = dependencyKeys(plugin);
        for (Key<?> key : injector.getBindings().keySet()) {
            if (Config.class.isAssignableFrom(key.getTypeLiteral().getRawType()) && !fromDependencies.contains(key)) {
                try {
                    return (Config) injector.getInstance(key);
                } catch (RuntimeException | LinkageError e) {
                    log.log(Level.WARNING, "Can't create " + plugin.getName() + "'s config", e);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * The keys the public modules of a plugin's dependencies bind into its injector.
     *
     * @param plugin a loaded plugin
     * @return the keys
     */
    private Set<Key<?>> dependencyKeys(Plugin plugin) {
        Set<Key<?>> keys = new HashSet<>();
        for (PluginDependency dependency : plugin.getClass().getAnnotationsByType(PluginDependency.class)) {
            for (Plugin loaded : plugins) {
                Module module = dependency.value().isInstance(loaded) ? loaded.getPublicModule() : null;
                if (module != null) {
                    for (Element element : Elements.getElements(module)) {
                        if (element instanceof Binding<?> binding) {
                            keys.add(binding.getKey());
                        }
                    }
                }
            }
        }
        return keys;
    }

    /**
     * Every loaded plugin, in dependency order.
     *
     * @return the plugins, hidden ones included
     */
    public List<Plugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    /**
     * The plugins that can't be on with this one: those whose name or conflicts match this one's name or conflicts.
     *
     * @param plugin a loaded plugin
     * @return the conflicting plugins
     */
    public List<Plugin> conflictsForPlugin(Plugin plugin) {
        Set<String> names = conflictNames(plugin);
        List<Plugin> conflicts = new ArrayList<>();
        for (Plugin other : plugins) {
            if (other != plugin && !Collections.disjoint(names, conflictNames(other))) {
                conflicts.add(other);
            }
        }
        return conflicts;
    }

    /**
     * Runs a listener after plugins load, start, stop, fail or are turned on or off, on the EDT.
     *
     * @param listener the listener
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private boolean start(Plugin plugin) {
        if (active.contains(plugin) || !isPluginEnabled(plugin) || plugin.injector == null) {
            return false;
        }
        for (Plugin conflict : conflictsForPlugin(plugin)) {
            if (isPluginEnabled(conflict)) {
                setPluginEnabled(conflict, false);
            }
            stop(conflict);
        }
        active.add(plugin);
        try {
            plugin.startUp();
            eventBus.register(plugin);
        } catch (Exception | LinkageError e) {
            log.log(Level.WARNING, "Plugin " + plugin.getName() + " failed to start; switching it off", e);
            stop(plugin);
            failures.put(plugin, e);
            setPluginEnabled(plugin, false);
            return false;
        }
        failures.remove(plugin);
        log.info("Started plugin " + plugin.getName());
        eventBus.post(new PluginChanged(plugin, true));
        changed();
        return true;
    }

    private boolean stop(Plugin plugin) {
        if (!active.remove(plugin)) {
            return false;
        }
        eventBus.unregister(plugin);
        try {
            plugin.shutDown();
        } catch (Exception | LinkageError e) {
            log.log(Level.WARNING, "Plugin " + plugin.getName() + " failed to stop cleanly", e);
        }
        log.info("Stopped plugin " + plugin.getName());
        eventBus.post(new PluginChanged(plugin, false));
        changed();
        return true;
    }

    /**
     * Creates a plugin and builds its injector.
     *
     * @param clazz  the plugin class
     * @param loaded the plugins loaded so far, by class; every dependency of {@code clazz} is among them
     * @return the plugin (with a failure and no injector if its injector can't be built), or null if it can't be
     *         created at all
     */
    @SuppressWarnings("unchecked")
    private Plugin instantiate(Class<? extends Plugin> clazz, Map<Class<?>, Plugin> loaded) {
        Plugin plugin;
        try {
            Constructor<? extends Plugin> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            plugin = constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Could not create plugin " + clazz.getName(), e);
            return null;
        }
        try {
            List<Module> modules = new ArrayList<>();
            for (PluginDependency dependency : clazz.getAnnotationsByType(PluginDependency.class)) {
                Module module = loaded.get(dependency.value()).getPublicModule();
                if (module == null) {
                    throw new IllegalStateException("its dependency " + dependency.value().getSimpleName()
                            + " offers no services (getPublicModule() is null)");
                }
                modules.add(module);
            }
            modules.add(binder -> {
                binder.bind((Class<Plugin>) clazz).toInstance(plugin);
                binder.install(plugin);
            });
            plugin.injector = parent.createChildInjector(modules);
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Could not load plugin " + plugin.getName(), e);
            failures.put(plugin, e);
        }
        return plugin;
    }

    /**
     * Sorts plugin classes so each comes after the plugins it depends on. A class whose dependency is neither loaded
     * nor among the classes, or that depends on itself through others, is logged and left out.
     *
     * @param candidates the classes to load
     * @param loaded     the classes already loaded
     * @return the loadable classes, dependencies first, otherwise by name
     */
    private static List<Class<? extends Plugin>> dependencyOrder(Set<Class<? extends Plugin>> candidates,
                                                                 Set<Class<?>> loaded) {
        List<Class<? extends Plugin>> byName = new ArrayList<>(candidates);
        byName.sort(Comparator.comparing((Class<? extends Plugin> c) -> c.getAnnotation(PluginDescriptor.class).name())
                .thenComparing(Class::getName));
        List<Class<? extends Plugin>> order = new ArrayList<>();
        Set<Class<?>> placed = new HashSet<>(loaded);
        Set<Class<?>> failed = new HashSet<>();
        for (Class<? extends Plugin> clazz : byName) {
            place(clazz, candidates, placed, failed, new HashSet<>(), order);
        }
        return order;
    }

    private static boolean place(Class<? extends Plugin> clazz, Set<Class<? extends Plugin>> candidates,
                                 Set<Class<?>> placed, Set<Class<?>> failed, Set<Class<?>> visiting,
                                 List<Class<? extends Plugin>> order) {
        if (placed.contains(clazz)) {
            return true;
        }
        if (failed.contains(clazz) || !candidates.contains(clazz)) {
            return false;
        }
        if (!visiting.add(clazz)) {
            log.warning("Plugin " + clazz.getSimpleName() + " depends on itself through other plugins");
            return false;
        }
        for (PluginDependency dependency : clazz.getAnnotationsByType(PluginDependency.class)) {
            if (!place(dependency.value(), candidates, placed, failed, visiting, order)) {
                log.warning("Not loading plugin " + clazz.getSimpleName() + ": it depends on "
                        + dependency.value().getSimpleName() + ", which can't be loaded");
                failed.add(clazz);
                return false;
            }
        }
        placed.add(clazz);
        order.add(clazz);
        return true;
    }

    private static PluginDescriptor descriptor(Plugin plugin) {
        return plugin.getClass().getAnnotation(PluginDescriptor.class);
    }

    private static String configKey(Plugin plugin) {
        return plugin.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static Set<String> conflictNames(Plugin plugin) {
        PluginDescriptor descriptor = descriptor(plugin);
        Set<String> names = new HashSet<>(Arrays.asList(descriptor.conflicts()));
        names.add(descriptor.name());
        return names;
    }

    private void changed() {
        edt.execute(() -> changeListeners.forEach(Runnable::run));
    }

    /**
     * Runs work on the EDT and waits for it; at once if this is the EDT.
     *
     * @param task the work
     * @param <T>  what it returns
     * @return what it returned
     */
    private <T> T onEdt(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        edt.execute(future);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the EDT", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
