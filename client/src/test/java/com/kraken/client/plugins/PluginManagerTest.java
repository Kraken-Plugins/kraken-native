package com.kraken.client.plugins;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.eventbus.Subscribe;
import com.kraken.client.AttachedGame;
import com.kraken.client.KrakenConfig;
import com.kraken.client.SessionModule;
import com.kraken.client.events.PluginChanged;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.worldhopper.WorldHopperConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link PluginManager} with fake plugins and no Swing: "EDT" work runs on the calling thread. The fakes are
 * nested classes, which the built-in plugin scan skips.
 */
class PluginManagerTest {

    static final List<String> events = new ArrayList<>();

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
    private ConfigManager config;
    private Injector parent;
    private PluginManager manager;

    @BeforeEach
    void setUp() {
        events.clear();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        manager = newManager(false);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * A manager over the same settings file and bus, as the next launch would have.
     *
     * @param developerMode whether developer plugins load
     * @return the manager, with no plugins loaded; load them under {@link #parent}, a session injector as the client
     *         builds once the API attaches
     */
    private PluginManager newManager(boolean developerMode) {
        config = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
        Injector root = Guice.createInjector(binder -> {
            binder.bind(EventBus.class).toInstance(bus);
            binder.bind(ConfigManager.class).toInstance(config);
            binder.bind(KrakenConfig.class).toInstance(config.getConfig(KrakenConfig.class));
            binder.bind(WorldHopperConfig.class).toInstance(config.getConfig(WorldHopperConfig.class));
            binder.bind(ScheduledExecutorService.class).toInstance(scheduler);
        });
        parent = root.createChildInjector(new SessionModule(attachedSession(), 0));
        return new PluginManager(bus, config, Runnable::run, developerMode);
    }

    /**
     * A stand-in for the attached game: plugins load only once the API has attached, so they always see a real
     * session. It is built over a memory image that reads zeros, so it reports no game state, and its poll loop never
     * starts.
     *
     * @return a {@link GameSession} over that memory, posting on {@link #bus}
     */
    private GameSession attachedSession() {
        return AttachedGame.session(bus);
    }

    private Plugin plugin(Class<? extends Plugin> type) {
        return manager.getPlugins().stream().filter(type::isInstance).findFirst().orElseThrow();
    }

    record Ping(String text) {
    }

    interface Greeter {
        String greet();
    }

    @PluginDescriptor(name = "Good", tags = {"example"})
    static class GoodPlugin extends Plugin {
        @Inject
        EventBus bus;

        @Inject
        Client client;

        @Override
        protected void startUp() {
            events.add("start Good, bus " + (bus != null) + ", client " + (client.getClass() == Client.class));
        }

        @Override
        protected void shutDown() {
            events.add("stop Good");
        }

        @Subscribe
        public void onPing(Ping ping) {
            events.add("Good got " + ping.text());
        }
    }

    @PluginDescriptor(name = "Failing")
    static class FailingPlugin extends Plugin {
        @Override
        protected void startUp() {
            throw new IllegalStateException("no luck");
        }

        @Override
        protected void shutDown() {
            events.add("stop Failing");
        }
    }

    @PluginDescriptor(name = "Service")
    static class ServicePlugin extends Plugin {
        @Override
        protected void startUp() {
            events.add("start Service");
        }

        @Override
        protected void shutDown() {
            events.add("stop Service");
        }

        @Override
        protected Module getPublicModule() {
            return binder -> binder.bind(Greeter.class).toInstance(() -> "hello");
        }
    }

    // Named to sort before "Service", so the order comes from the dependency
    @PluginDependency(ServicePlugin.class)
    @PluginDescriptor(name = "A dependent")
    static class DependentPlugin extends Plugin {
        @Inject
        Greeter greeter;

        @Override
        protected void startUp() {
            events.add("start Dependent, " + greeter.greet());
        }

        @Override
        protected void shutDown() {
            events.add("stop Dependent");
        }
    }

    @PluginDependency(GoodPlugin.class)
    @PluginDescriptor(name = "Needy")
    static class NeedyPlugin extends Plugin {
    }

    @PluginDescriptor(name = "Left", conflicts = "sides")
    static class LeftPlugin extends Plugin {
    }

    @PluginDescriptor(name = "Right", conflicts = "sides")
    static class RightPlugin extends Plugin {
    }

    @PluginDescriptor(name = "Off", enabledByDefault = false)
    static class OffPlugin extends Plugin {
        @Override
        protected void startUp() {
            events.add("start Off");
        }
    }

    @PluginDescriptor(name = "Dev", developerPlugin = true)
    static class DevPlugin extends Plugin {
    }

    @PluginDescriptor(name = "Provider")
    static class ProviderPlugin extends Plugin {
        @Inject
        String greeting;

        @Provides
        String provideGreeting() {
            return "provided";
        }

        @Override
        protected void startUp() {
            events.add("start Provider, " + greeting);
        }
    }

    // Package-private, as a plugin's config may be: its default bodies still run
    @ConfigGroup("configured")
    interface ConfiguredConfig extends Config {
        @ConfigItem(keyName = "word", name = "Word", description = "")
        default String word() {
            return "default";
        }
    }

    @PluginDescriptor(name = "Configured")
    static class ConfiguredPlugin extends Plugin {
        @Inject
        ConfiguredConfig config;

        @Provides
        ConfiguredConfig provideConfig(ConfigManager configManager) {
            return configManager.getConfig(ConfiguredConfig.class);
        }

        @Override
        protected void startUp() {
            events.add("start Configured, " + config.word());
        }
    }

    @PluginDescriptor(name = "Config service")
    static class ConfigServicePlugin extends Plugin {
        @Override
        protected Module getPublicModule() {
            return binder -> binder.bind(ConfiguredConfig.class).toProvider(() -> null);
        }
    }

    @PluginDependency(ConfigServicePlugin.class)
    @PluginDescriptor(name = "Config user")
    static class ConfigUserPlugin extends Plugin {
    }

    @PluginDescriptor(name = "Broken config")
    static class BrokenConfigPlugin extends Plugin {
        @Provides
        ConfiguredConfig provideConfig() {
            throw new IllegalStateException("no config today");
        }
    }

    @Test
    void startsEnabledPluginsWithTheirInjectionsAndSubscribers() {
        List<String> changes = new ArrayList<>();
        bus.register(PluginChanged.class, e -> changes.add(e.getPlugin().getName() + " " + e.isLoaded()), 0);
        manager.loadPlugins(parent, List.of(GoodPlugin.class, OffPlugin.class));
        bus.post(new Ping("before"));
        manager.startPlugins();
        bus.post(new Ping("running"));

        Plugin good = plugin(GoodPlugin.class);
        assertTrue(manager.isPluginActive(good));
        assertFalse(manager.isPluginActive(plugin(OffPlugin.class)), "off by default");
        assertEquals(List.of("start Good, bus true, client true", "Good got running"), events);
        assertEquals(List.of("Good true"), changes);

        events.clear();
        assertTrue(manager.stopPlugin(good));
        assertFalse(manager.stopPlugin(good), "already stopped");
        bus.post(new Ping("stopped"));
        assertEquals(List.of("stop Good"), events);
        assertEquals(List.of("Good true", "Good false"), changes);
        assertTrue(manager.isPluginEnabled(good), "stopping doesn't turn it off");
    }

    @Test
    void dependenciesLoadAndStartFirstAndStopLast() {
        manager.loadPlugins(parent, List.of(DependentPlugin.class, ServicePlugin.class));
        assertEquals(List.of("Service", "A dependent"), manager.getPlugins().stream().map(Plugin::getName).toList());
        manager.startPlugins();
        assertEquals(List.of("start Service", "start Dependent, hello"), events);
        events.clear();
        manager.stopPlugins();
        assertEquals(List.of("stop Dependent", "stop Service"), events);
    }

    @Test
    void aPluginWhoseDependencyIsMissingIsNotLoaded() {
        manager.loadPlugins(parent, List.of(DependentPlugin.class));
        assertEquals(List.of(), manager.getPlugins());
    }

    @Test
    void aPluginWhoseDependencyOffersNoServicesIsListedAsFailed() {
        manager.loadPlugins(parent, List.of(GoodPlugin.class, NeedyPlugin.class));
        Plugin needy = plugin(NeedyPlugin.class);
        assertNull(needy.getInjector());
        assertNotNull(manager.getFailure(needy));
        assertFalse(manager.startPlugin(needy));
    }

    @Test
    void aFailingPluginIsSwitchedOffAndItsErrorKept() {
        manager.loadPlugins(parent, List.of(FailingPlugin.class));
        Plugin failing = plugin(FailingPlugin.class);
        manager.startPlugins();

        assertFalse(manager.isPluginActive(failing));
        assertEquals("no luck", manager.getFailure(failing).getMessage());
        assertFalse(manager.isPluginEnabled(failing), "switched off");
        assertEquals(List.of("stop Failing"), events, "shutDown runs after a failed startUp");

        config.flush();
        manager = newManager(false);
        manager.loadPlugins(parent, List.of(FailingPlugin.class));
        assertFalse(manager.isPluginEnabled(plugin(FailingPlugin.class)), "still off on the next launch");
    }

    @Test
    void enabledStatePersistsAcrossLaunches() throws IOException {
        manager.loadPlugins(parent, List.of(GoodPlugin.class, OffPlugin.class));
        manager.setPluginEnabled(plugin(OffPlugin.class), true);
        manager.setPluginEnabled(plugin(GoodPlugin.class), false);
        config.flush();
        assertTrue(Files.readString(dir.resolve("settings.properties")).contains("kraken.offplugin=true"));

        manager = newManager(false);
        manager.loadPlugins(parent, List.of(GoodPlugin.class, OffPlugin.class));
        manager.startPlugins();
        assertTrue(manager.isPluginActive(plugin(OffPlugin.class)));
        assertFalse(manager.isPluginActive(plugin(GoodPlugin.class)));
        assertEquals(List.of("start Off"), events);
    }

    @Test
    void turningOnAPluginTurnsOffTheOnesItConflictsWith() {
        manager.loadPlugins(parent, List.of(LeftPlugin.class, RightPlugin.class));
        Plugin left = plugin(LeftPlugin.class);
        Plugin right = plugin(RightPlugin.class);
        assertEquals(List.of(right), manager.conflictsForPlugin(left));

        manager.startPlugins();
        assertTrue(manager.isPluginActive(left), "started first, which switched off the other");
        assertFalse(manager.isPluginActive(right));
        assertFalse(manager.isPluginEnabled(right));

        manager.setPluginEnabled(right, true);
        assertFalse(manager.isPluginEnabled(left));
        assertTrue(manager.startPlugin(right));
        assertTrue(manager.isPluginActive(right));
        assertFalse(manager.isPluginActive(left), "turning one on stops the other");
    }

    @Test
    void developerPluginsOnlyLoadInDeveloperMode() {
        manager.loadPlugins(parent, List.of(DevPlugin.class));
        assertEquals(List.of(), manager.getPlugins());
        manager = newManager(true);
        manager.loadPlugins(parent, List.of(DevPlugin.class));
        assertEquals(1, manager.getPlugins().size());
    }

    @Test
    void aPluginsProvidesMethodsAreInstalled() {
        manager.loadPlugins(parent, List.of(ProviderPlugin.class));
        manager.startPlugins();
        assertEquals(List.of("start Provider, provided"), events);
    }

    @Test
    void aPluginsConfigIsFoundThroughItsInjector() {
        manager.loadPlugins(parent, List.of(ConfiguredPlugin.class, GoodPlugin.class));
        manager.startPlugins();
        assertEquals(List.of("start Configured, default", "start Good, bus true, client true"), events);

        Config found = manager.getPluginConfigProxy(plugin(ConfiguredPlugin.class));
        assertTrue(found instanceof ConfiguredConfig, String.valueOf(found));
        config.setConfiguration("configured", "word", "stored");
        assertEquals("stored", ((ConfiguredConfig) found).word());
        assertNull(manager.getPluginConfigProxy(plugin(GoodPlugin.class)), "provides no config");
    }

    @Test
    void aConfigFromADependencyOrOneThatCantBeCreatedIsNotTheirs() {
        manager.loadPlugins(parent, List.of(ConfigServicePlugin.class, ConfigUserPlugin.class, BrokenConfigPlugin.class));
        assertNull(manager.getPluginConfigProxy(plugin(ConfigUserPlugin.class)), "the dependency's, not its own");
        assertNull(manager.getPluginConfigProxy(plugin(BrokenConfigPlugin.class)), "its provider throws");
    }

    @Test
    void loadingAClassTwiceLoadsItOnce() {
        manager.loadPlugins(parent, List.of(GoodPlugin.class));
        manager.loadPlugins(parent, List.of(GoodPlugin.class));
        assertEquals(1, manager.getPlugins().size());
    }

    @Test
    void theCoreScanFindsTheBuiltInPluginsAndNotTheseFakes() {
        manager.loadCorePlugins(parent);
        List<String> names = manager.getPlugins().stream().map(Plugin::getName).toList();
        assertTrue(names.contains("Tick Counter"), names.toString());
        assertTrue(names.contains("NPC Indicators"), names.toString());
        assertTrue(names.contains("Object Markers"), names.toString());
        assertFalse(names.contains("Good"), names.toString());
        for (Plugin plugin : manager.getPlugins()) {
            assertNotNull(plugin.getInjector(), plugin.getName() + " loads");
            assertNotNull(manager.getPluginConfigProxy(plugin), plugin.getName() + " has settings");
        }
    }
}
