package com.kraken.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.plugins.PluginManager;
import com.kraken.client.plugins.config.ConfigManager;
import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Closes Kraken: when the window closes or the game exits.
 */
@Log(topic = "kraken")
@Singleton
public final class ExitHandler {

    private static final long FORCE_EXIT_MS = 15_000;

    private final PluginManager pluginManager;
    private final ConfigManager configManager;
    private final AtomicBoolean exiting = new AtomicBoolean();
    private final List<Runnable> exitTasks = new CopyOnWriteArrayList<>();

    @Inject
    ExitHandler(PluginManager pluginManager, ConfigManager configManager) {
        this.pluginManager = pluginManager;
        this.configManager = configManager;
    }

    /**
     * Adds a task to run as Kraken closes: after the plugins stop, before the settings are flushed and the JVM's
     * shutdown hooks close the game. Tasks run on the shutdown thread in the order they were added; one that throws is
     * logged and the rest still run.
     *
     * @param task the task
     */
    public void addExitTask(Runnable task) {
        exitTasks.add(task);
    }

    /**
     * Starts closing Kraken. Runs on a thread of its own, so the EDT stays free for the plugins' {@code shutDown}. Only
     * the first call does anything. If shutting down takes more than 15 seconds, Kraken exits anyway.
     */
    public void exit() {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        log.info("Kraken is closing");
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
            try {
                pluginManager.stopPlugins();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Stopping plugins failed", e);
            }
            for (Runnable task : exitTasks) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "An exit task failed", e);
                }
            }
            configManager.flush();
            System.exit(0);
        }, "kraken-shutdown");
        shutdown.start();
    }
}
