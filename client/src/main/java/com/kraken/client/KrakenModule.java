package com.kraken.client;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.KrakenApi;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.config.ConfigManager;
import com.kraken.client.config.KrakenConfig;
import lombok.AllArgsConstructor;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Bindings for the client, and the parent of every plugin's injector. The frame, its panels and the launcher are
 * {@link Singleton}s that Guice creates on first use, so request the frame on the EDT before anything else.
 *
 * <p>Plugins can inject: {@link Client} (a stand-in until the API attaches), {@link EventBus},
 * {@code ClientThread}, {@code ConfigManager}, {@link KrakenConfig}, {@code OverlayManager}, {@code PluginManager} and a
 * shared {@link ScheduledExecutorService}.
 */
@AllArgsConstructor
public final class KrakenModule extends AbstractModule {
    private final File exe;
    private final boolean developerMode;

    @Override
    protected void configure() {
        bind(File.class).annotatedWith(Names.named("exe")).toInstance(exe);
        bindConstant().annotatedWith(Names.named("developerMode")).to(developerMode);
        bind(Path.class).annotatedWith(Names.named("settings")).toInstance(settingsFile());
        // One bus for the game events the API posts and the client's own events
        bind(EventBus.class).toInstance(new EventBus());
        // Plugin start-up and shut-down run here: on the EDT, at once when already on it
        bind(Executor.class).annotatedWith(Names.named("edt")).toInstance(KrakenModule::onEdt);
    }

    /**
     * The client plugins see: the attached API's, or a stand-in answering "logged out" until the API attaches.
     *
     * @param connection the client's link to the game
     * @return the stand-in client
     */
    @Provides
    @Singleton
    Client provideClient(ApiConnection connection) {
        return StandInClient.create(() -> {
            KrakenApi api = connection.api();
            return api == null ? null : api.client();
        }, () -> switch (connection.getState()) {
            case UNSUPPORTED, FAILED -> GameState.UNKNOWN;
            default -> GameState.STARTING;
        });
    }

    /**
     * Kraken's own settings, which the Settings page edits.
     *
     * @param configManager the settings store
     * @return the settings
     */
    @Provides
    @Singleton
    KrakenConfig provideKrakenConfig(ConfigManager configManager) {
        return configManager.getConfig(KrakenConfig.class);
    }

    /**
     * A shared scheduler for background work: settings writes, and plugin work off the client thread.
     *
     * @return a single daemon thread, {@code kraken-scheduler}
     */
    @Provides
    @Singleton
    ScheduledExecutorService provideScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "kraken-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Where settings are kept: {@code ~/.kraken-native/settings.properties}, next to the updater's cache, unless
     * {@code -Dkraken.settings=<file>} says otherwise.
     *
     * @return the settings file
     */
    private static Path settingsFile() {
        String override = System.getProperty("kraken.settings");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".kraken-native", "settings.properties");
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
