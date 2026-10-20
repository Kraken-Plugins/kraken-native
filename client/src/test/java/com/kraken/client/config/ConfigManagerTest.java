package com.kraken.client.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConfigManager}'s settings file: values survive a restart, and writes are delayed and atomic.
 */
class ConfigManagerTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private Path file;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        file = dir.resolve("nested").resolve("settings.properties");
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void valuesSurviveARestart() {
        ConfigManager config = new ConfigManager(file, scheduler);
        assertNull(config.getConfiguration("kraken", "tickcounterplugin"));
        config.setConfiguration("kraken", "tickcounterplugin", "true");
        config.setConfiguration("npchighlight", "names", "Goblin, Guard");
        config.setConfiguration("npchighlight", "gone", "x");
        config.unsetConfiguration("npchighlight", "gone");
        assertEquals("true", config.getConfiguration("kraken", "tickcounterplugin"));
        config.flush();

        ConfigManager next = new ConfigManager(file, scheduler);
        assertEquals("true", next.getConfiguration("kraken", "tickcounterplugin"));
        assertEquals("Goblin, Guard", next.getConfiguration("npchighlight", "names"));
        assertNull(next.getConfiguration("npchighlight", "gone"));
    }

    @Test
    void changesAreWrittenShortlyWithoutAFlush() throws Exception {
        ConfigManager config = new ConfigManager(file, scheduler);
        config.setConfiguration("kraken", "a", "1");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("1", new ConfigManager(file, scheduler).getConfiguration("kraken", "a"));
    }

    @Test
    void writesLeaveNoTemporaryFiles() throws IOException {
        ConfigManager config = new ConfigManager(file, scheduler);
        config.setConfiguration("kraken", "a", "1");
        config.flush();
        config.setConfiguration("kraken", "a", "2");
        config.flush();
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
        assertTrue(Files.readString(file).contains("kraken.a=2"));
    }
}
