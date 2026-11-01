package com.kraken.client.plugins.mossgiantkiller;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.GameTick;
import com.kraken.client.AttachedGame;
import com.kraken.client.KrakenConfig;
import com.kraken.client.SessionModule;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginManager;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;
import com.kraken.client.worldhopper.WorldHopperConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Moss Giant Killer loaded as the client loads plugins, under a session injector over a game that reads zeros:
 * every injection resolves (the services, the overlay, the config), it starts and stops cleanly, its overlay follows
 * the setting, and a tick with nobody logged in does nothing.
 */
class MossGiantKillerPluginTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
    private PluginManager manager;
    private OverlayManager overlays;
    private ConfigManager config;
    private MossGiantKillerPlugin plugin;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        config = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
        Injector root = Guice.createInjector(binder -> {
            binder.bind(EventBus.class).toInstance(bus);
            binder.bind(ConfigManager.class).toInstance(config);
            binder.bind(KrakenConfig.class).toInstance(config.getConfig(KrakenConfig.class));
            binder.bind(WorldHopperConfig.class).toInstance(config.getConfig(WorldHopperConfig.class));
            binder.bind(ScheduledExecutorService.class).toInstance(scheduler);
        });
        Injector session = root.createChildInjector(new SessionModule(AttachedGame.session(bus), 0));
        overlays = session.getInstance(OverlayManager.class);
        manager = new PluginManager(bus, config, Runnable::run, false);
        List<Plugin> loaded = manager.loadPlugins(session, List.of(MossGiantKillerPlugin.class));
        plugin = (MossGiantKillerPlugin) loaded.get(0);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void itIsOffUntilSwitchedOn() {
        assertFalse(manager.isPluginEnabled(plugin));
        assertFalse(manager.isPluginActive(plugin));
    }

    @Test
    void itStartsShowsItsStatusAndStops() {
        manager.setPluginEnabled(plugin, true);
        assertTrue(manager.startPlugin(plugin), "every injection resolves");
        assertNull(manager.getFailure(plugin));
        assertTrue(overlays.getOverlays().stream().anyMatch(MossGiantKillerOverlay.class::isInstance));

        config.setConfiguration(MossGiantKillerConfig.GROUP, "showOverlay", false);
        assertFalse(overlays.getOverlays().stream().anyMatch(MossGiantKillerOverlay.class::isInstance));

        assertTrue(manager.stopPlugin(plugin));
        assertFalse(overlays.getOverlays().stream().anyMatch(MossGiantKillerOverlay.class::isInstance));
    }

    @Test
    void aTickWithNobodyLoggedInDoesNothing() {
        manager.setPluginEnabled(plugin, true);
        manager.startPlugin(plugin);
        bus.post(new GameTick());
        assertEquals(MossGiantKillerPlugin.State.STARTING, plugin.getState());
        assertEquals(0, plugin.getKills());
        assertNull(manager.getFailure(plugin));
    }
}
