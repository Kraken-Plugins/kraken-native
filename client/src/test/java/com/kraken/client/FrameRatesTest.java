package com.kraken.client;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame rates Kraken shows and the cap it asks for, headless: with no window there is never an active one, so
 * Kraken counts as unfocused once the focus has settled.
 */
class FrameRatesTest {

    private static final long MS = 1_000_000L;

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager manager;
    private KrakenConfig config;
    private FrameRates rates;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        EventBus bus = new EventBus();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
        config = manager.getConfig(KrakenConfig.class);
        rates = new FrameRates(config, bus);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void meterCountsTheIntervalsInEachFullSecond() {
        FrameRates.RateMeter meter = new FrameRates.RateMeter();
        assertEquals(0, meter.rate(0), "nothing marked yet");
        for (long t = 0; t <= 1000; t += 20) {
            meter.mark(t * MS);
        }
        assertEquals(50, meter.rate(1000 * MS), 1e-9);
        assertEquals(50, meter.rate(1900 * MS), 1e-9, "holds until the next window ends");
        assertEquals(0, meter.rate(2100 * MS), "nothing for over a second reads as idle");
    }

    @Test
    void meterStartsAFreshWindowAfterAPause() {
        FrameRates.RateMeter meter = new FrameRates.RateMeter();
        for (long t = 0; t <= 1000; t += 20) {
            meter.mark(t * MS);
        }
        for (long t = 5000; t <= 6000; t += 100) {
            meter.mark(t * MS);
        }
        assertEquals(10, meter.rate(6000 * MS), 1e-9, "the pause isn't averaged in");
    }

    @Test
    void gameRateComesFromTheHooksReportsUntilItDisconnects() {
        assertTrue(Double.isNaN(rates.getGameFrameRate()), "unknown before any report");
        rates.gameFrames(59, 1_000_000);
        assertEquals(59, rates.getGameFrameRate(), 1e-9);
        rates.gameFrames(30, 2_000_000);
        assertEquals(15, rates.getGameFrameRate(), 1e-9);
        rates.gameDisconnected();
        assertTrue(Double.isNaN(rates.getGameFrameRate()));
    }

    @Test
    void capFollowsTheSettingsWhileUnfocused() throws Exception {
        List<Integer> heard = new CopyOnWriteArrayList<>();
        rates.addFrameLimitListener(heard::add);
        assertEquals(0, rates.getFrameLimit(), "off by default");

        manager.setConfiguration(KrakenConfig.GROUP, "unfocusedFps", 15);
        manager.setConfiguration(KrakenConfig.GROUP, "limitUnfocusedFps", true);
        awaitLimit(15);
        manager.setConfiguration(KrakenConfig.GROUP, "unfocusedFps", 25);
        awaitLimit(25);
        manager.setConfiguration(KrakenConfig.GROUP, "limitUnfocusedFps", false);
        awaitLimit(0);
        assertEquals(List.of(15, 25, 0), heard);
    }

    /**
     * Waits for the cap to reach a value: the focus settles on the EDT a moment after the rates are created.
     *
     * @param expected the cap
     * @throws InterruptedException if interrupted
     */
    private void awaitLimit(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (rates.getFrameLimit() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, rates.getFrameLimit());
    }
}
