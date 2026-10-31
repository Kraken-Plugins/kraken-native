package com.kraken.client;

import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.Point;
import com.kraken.api.action.ClickPolicy;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings page's action settings reaching the client's interactions: the defaults on attach, and each change
 * after it.
 */
class ActionSettingsTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
    private ConfigManager manager;
    private KrakenConfig config;
    private Client client;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
        config = manager.getConfig(KrakenConfig.class);
        GameSession session = AttachedGame.session(bus);
        client = session.client();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void theDefaultsReachTheClientWhenItAttaches() {
        new ActionSettings(config, bus, client);
        assertEquals(ClickPolicy.ON_SCREEN, client.getInteractions().getClickPolicy());
        assertEquals(new Point(0, 0), client.getInteractions().getStaticClickPoint());
    }

    @Test
    void aChangedSettingReachesTheClient() {
        new ActionSettings(config, bus, client);
        manager.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.STATIC);
        manager.setConfiguration(KrakenConfig.GROUP, "staticClickPoint", new java.awt.Point(12, 34));
        assertEquals(ClickPolicy.STATIC, client.getInteractions().getClickPolicy());
        assertEquals(new Point(12, 34), client.getInteractions().getStaticClickPoint());

        manager.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.NONE);
        assertEquals(ClickPolicy.NONE, client.getInteractions().getClickPolicy());
    }

    @Test
    void otherGroupsAreNotApplied() {
        new ActionSettings(config, bus, client);
        manager.setConfiguration("actiontester", "clickPolicy", ClickPolicy.NONE);
        assertEquals(ClickPolicy.ON_SCREEN, client.getInteractions().getClickPolicy());
    }

    @Test
    void thePointShowsOnlyWhenAClickIsSentAtIt() {
        manager.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.NONE);
        assertFalse(manager.isShown(config, "staticClickPoint"));
        manager.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.ON_SCREEN);
        assertTrue(manager.isShown(config, "staticClickPoint"));
        manager.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.STATIC);
        assertTrue(manager.isShown(config, "staticClickPoint"));
    }
}
