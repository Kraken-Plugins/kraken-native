package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.config.ConfigManager;
import com.kraken.client.events.ClientShutdown;
import com.kraken.client.plugins.PluginManager;
import lombok.extern.java.Log;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Closes Kraken: when the window closes or the game exits. Posts {@link ClientShutdown}, waits for the work
 * subscribers hand over, stops the plugins, writes the settings and exits. The game process is ended by the launcher's
 * shutdown hook.
 */
@Log(topic = "kraken")
@Singleton
public final class ExitHandler {

    private static final Duration SHUTDOWN_TASKS = Duration.ofSeconds(5);
    private static final long FORCE_EXIT_MS = 15_000;

    private final EventBus eventBus;
    private final PluginManager pluginManager;
    private final ConfigManager configManager;
    private final AtomicBoolean exiting = new AtomicBoolean();

    @Inject
    ExitHandler(EventBus eventBus, PluginManager pluginManager, ConfigManager configManager) {
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;
        this.configManager = configManager;
    }

    /**
     * Starts closing Kraken. {@link ClientShutdown} is posted on the calling thread; the rest runs on a thread of its
     * own, so the EDT stays free for the plugins' {@code shutDown}. Only the first call does anything. If shutting down
     * takes more than 15 seconds, Kraken exits anyway.
     */
    public void exit() {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        log.info("Kraken is closing");
        ClientShutdown event = new ClientShutdown();
        eventBus.post(event);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(FORCE_EXIT_MS);
            } catch (InterruptedException e) {
                return;
            }
            log.warning("Shutting down took too long; exiting anyway");
            System.exit(0);
        }, "kraken-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Thread shutdown = new Thread(() -> {
            event.waitForAllConsumers(SHUTDOWN_TASKS);
            try {
                pluginManager.stopPlugins();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Stopping plugins failed", e);
            }
            configManager.flush();
            System.exit(0);
        }, "kraken-shutdown");
        shutdown.start();
    }
}
