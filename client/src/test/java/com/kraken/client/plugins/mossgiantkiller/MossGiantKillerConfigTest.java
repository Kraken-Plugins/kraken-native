package com.kraken.client.plugins.mossgiantkiller;

import com.kraken.api.action.Spell;
import com.kraken.api.action.Spellbook;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Moss Giant Killer's settings: their defaults, the escape spell showing only while it's used, and the loot
 * setting read as names.
 */
class MossGiantKillerConfigTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager manager;
    private MossGiantKillerConfig config;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        config = manager.getConfig(MossGiantKillerConfig.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void theDefaultsFightMossGiantsAndEatAtTwenty() {
        assertEquals("Moss giant", config.npcName());
        assertEquals(20, config.eatAt());
        assertEquals(10, config.radius());
        assertTrue(config.teleportWhenOutOfFood());
        assertEquals(Spell.VARROCK_TELEPORT, config.escapeSpell());
        assertEquals(Spellbook.STANDARD, config.escapeSpell().getSpellbook());
        assertTrue(config.buryBones());
        assertFalse(config.protectFromMelee());
    }

    @Test
    void theEscapeSpellShowsWhileItIsUsed() {
        assertTrue(manager.isShown(config, "escapeSpell"));
        manager.setConfiguration(MossGiantKillerConfig.GROUP, "teleportWhenOutOfFood", false);
        assertFalse(manager.isShown(config, "escapeSpell"));
    }

    @Test
    void theLootSettingIsReadAsNames() {
        assertEquals(Set.of("big bones", "nature rune", "mossy key"),
                MossGiantKillerPlugin.names(" Big bones,Nature rune , ,MOSSY KEY,"));
        assertTrue(MossGiantKillerPlugin.names("").isEmpty());
        assertTrue(MossGiantKillerPlugin.names(config.loot()).contains("big bones"));
    }
}
