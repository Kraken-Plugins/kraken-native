package com.kraken.client.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.extern.java.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Kraken's settings, as RuneLite's {@code ConfigManager}: string values under a group and a key, kept in one
 * properties file ({@code ~/.kraken-native/settings.properties}, keys {@code group.key}). The plugin manager keeps
 * each plugin's on/off state here, in group {@code kraken}.
 *
 * <p>Changes are written shortly after they're made (several changes in a row are written once), atomically: to a
 * temporary file that then replaces the settings file. {@link #flush} writes at once.
 *
 * <p>Thread-safe.
 */
@Log(topic = "kraken")
@Singleton
public class ConfigManager {

    private static final long SAVE_DELAY_MS = 500;

    private final Path file;
    private final ScheduledExecutorService executor;
    private final Properties properties = new Properties();
    private ScheduledFuture<?> pendingSave;

    /**
     * Loads the settings file, if there is one.
     *
     * @param file     the settings file
     * @param executor runs the delayed writes
     */
    @Inject
    public ConfigManager(@Named("settings") Path file, ScheduledExecutorService executor) {
        this.file = file;
        this.executor = executor;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not read settings from " + file, e);
            }
        }
    }

    /**
     * A setting's value.
     *
     * @param group the group, e.g. a plugin's config group
     * @param key   the key within the group
     * @return the value, or null if it isn't set
     */
    public String getConfiguration(String group, String key) {
        return properties.getProperty(group + "." + key);
    }

    /**
     * Sets a setting. Setting it to its current value does nothing.
     *
     * @param group the group
     * @param key   the key within the group
     * @param value the new value
     */
    public synchronized void setConfiguration(String group, String key, String value) {
        Object old = properties.setProperty(group + "." + key, value);
        if (!value.equals(old)) {
            scheduleSave();
        }
    }

    /**
     * Removes a setting, so it reads as unset.
     *
     * @param group the group
     * @param key   the key within the group
     */
    public synchronized void unsetConfiguration(String group, String key) {
        if (properties.remove(group + "." + key) != null) {
            scheduleSave();
        }
    }

    /**
     * Writes any change not yet written. The client calls this as it exits.
     */
    public synchronized void flush() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
            save();
        }
    }

    private void scheduleSave() {
        if (pendingSave == null) {
            pendingSave = executor.schedule(this::saveScheduled, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void saveScheduled() {
        if (pendingSave != null) {
            pendingSave = null;
            save();
        }
    }

    private void save() {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "settings", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "Kraken settings");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not write settings to " + file, e);
        }
    }
}
