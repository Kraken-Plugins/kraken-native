package com.kraken.client;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.kraken.api.Client;
import com.kraken.api.GameSession;
import com.kraken.api.action.Bank;
import com.kraken.api.action.ClickPolicy;
import com.kraken.api.action.Dialogue;
import com.kraken.api.action.Equipment;
import com.kraken.api.action.Inventory;
import com.kraken.api.action.Magic;
import com.kraken.api.action.Movement;
import com.kraken.api.action.Prayers;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.worldhopper.WorldHopperConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The session injector hands plugins the attached client's services, the instances the client itself holds, and
 * applies the saved action settings from the attach on.
 */
class SessionModuleTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
    private ConfigManager config;
    private Injector root;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        config = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
        root = Guice.createInjector(binder -> {
            binder.bind(EventBus.class).toInstance(bus);
            binder.bind(ConfigManager.class).toInstance(config);
            binder.bind(KrakenConfig.class).toInstance(config.getConfig(KrakenConfig.class));
            binder.bind(WorldHopperConfig.class).toInstance(config.getConfig(WorldHopperConfig.class));
            binder.bind(ScheduledExecutorService.class).toInstance(scheduler);
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void pluginsGetTheClientsServices() {
        GameSession session = AttachedGame.session(bus);
        Injector plugin = root.createChildInjector(new SessionModule(session, 1234L))
                .createChildInjector(binder -> {
                });
        Client client = session.client();
        assertSame(client, plugin.getInstance(Client.class));
        assertSame(client.getMovement(), plugin.getInstance(Movement.class));
        assertSame(client.getDialogue(), plugin.getInstance(Dialogue.class));
        assertSame(client.getInventory(), plugin.getInstance(Inventory.class));
        assertSame(client.getMagic(), plugin.getInstance(Magic.class));
        assertSame(client.getPrayers(), plugin.getInstance(Prayers.class));
        assertSame(client.getBank(), plugin.getInstance(Bank.class));
        assertSame(client.getEquipment(), plugin.getInstance(Equipment.class));
    }

    @Test
    void pluginFieldsAreInjectedWithThem() {
        GameSession session = AttachedGame.session(bus);
        Services services = root.createChildInjector(new SessionModule(session, 1234L))
                .createChildInjector(binder -> {
                }).getInstance(Services.class);
        Client client = session.client();
        assertSame(client.getMagic(), services.magic);
        assertSame(client.getInventory(), services.inventory);
    }

    @Test
    void theSavedClickPolicyIsInForceFromTheAttachOn() {
        config.setConfiguration(KrakenConfig.GROUP, "clickPolicy", ClickPolicy.STATIC);
        GameSession session = AttachedGame.session(bus);
        root.createChildInjector(new SessionModule(session, 1234L));
        assertEquals(ClickPolicy.STATIC, session.client().getInteractions().getClickPolicy());
    }

    /**
     * A plugin-shaped class with injected service fields.
     */
    static final class Services {

        @Inject
        private Magic magic;

        @Inject
        private Inventory inventory;
    }
}
