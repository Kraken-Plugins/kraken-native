package com.kraken.client.plugins.actiontester;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigDescriptor;
import com.kraken.client.plugins.config.ConfigItemDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static com.kraken.client.plugins.actiontester.ActionTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the Action Tester's settings its page shows for each action: exactly the ones the action reads, plus the
 * action itself, Repeat and Run.
 */
class ActionTesterConfigTest {

    /**
     * The settings each action reads, besides the ones every action shows.
     */
    private static final Map<ActionTest, Set<String>> READS = new EnumMap<>(ActionTest.class);

    static {
        READS.put(LIST_MENU, Set.of());
        READS.put(MENU_ENTRY, Set.of("menuEntry"));
        READS.put(NPC_OPTION, Set.of("npc", "option"));
        READS.put(USE_ITEM_ON_NPC, Set.of("useItem", "npc"));
        READS.put(CAST_SPELL_ON_NPC, Set.of("spell", "npc"));
        READS.put(PLAYER_OPTION, Set.of("player", "option"));
        READS.put(USE_ITEM_ON_PLAYER, Set.of("useItem", "player"));
        READS.put(CAST_SPELL_ON_PLAYER, Set.of("spell", "player"));
        READS.put(OBJECT_OPTION, Set.of("object", "option"));
        READS.put(USE_ITEM_ON_OBJECT, Set.of("useItem", "object"));
        READS.put(CAST_SPELL_ON_OBJECT, Set.of("spell", "object"));
        READS.put(GROUND_ITEM_OPTION, Set.of("groundItem", "option"));
        READS.put(USE_ITEM_ON_GROUND_ITEM, Set.of("useItem", "groundItem"));
        READS.put(CAST_SPELL_ON_GROUND_ITEM, Set.of("spell", "groundItem"));
        READS.put(INVENTORY_OPTION, Set.of("item", "option"));
        READS.put(USE_ITEM_ON_ITEM, Set.of("useItem", "item"));
        READS.put(CAST_SPELL_ON_ITEM, Set.of("spell", "item"));
        READS.put(CAST_SPELL, Set.of("spell", "option"));
        READS.put(PRAYER_TOGGLE, Set.of("prayer"));
        READS.put(QUICK_PRAYERS, Set.of());
        READS.put(SPECIAL_ATTACK, Set.of());
        READS.put(WIDGET_OPTION, Set.of("component", "option"));
        READS.put(WIDGET_BUTTON, Set.of("component"));
        READS.put(PAUSE_BUTTON, Set.of("component"));
        READS.put(CLOSE_INTERFACE, Set.of());
        READS.put(RESUME_COUNT, Set.of("count"));
        READS.put(RESUME_NAME, Set.of("text"));
        READS.put(RESUME_STRING, Set.of("text"));
        READS.put(RESUME_OBJ, Set.of("itemId"));
        READS.put(WALK, Set.of("walkEast", "walkNorth"));
        READS.put(WALK_TO, Set.of("worldTile"));
        READS.put(TOGGLE_RUN, Set.of());
        READS.put(MOUSE_CLICK, Set.of("clickPoint", "rightClick"));
    }

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager manager;
    private ActionTesterConfig config;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        config = manager.getConfig(ActionTesterConfig.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private Set<String> shown() {
        ConfigDescriptor descriptor = manager.getConfigDescriptor(config);
        return descriptor.getItems().stream().filter(item -> manager.isShown(config, descriptor, item))
                .map(ConfigItemDescriptor::getKey).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void eachActionShowsTheSettingsItReads() {
        assertEquals(Set.of(ActionTest.values()), READS.keySet(), "every action is listed here");
        for (ActionTest test : ActionTest.values()) {
            manager.setConfiguration(ActionTesterConfig.GROUP, "test", test);
            Set<String> expected = new TreeSet<>(READS.get(test));
            expected.addAll(Set.of("test", "repeat", ActionTesterConfig.RUN));
            assertEquals(expected, shown(), test.name());
        }
    }

    @Test
    void theMouseClickBeforeAnActionIsTheSettingsPagesNotThisOne() {
        ConfigDescriptor descriptor = manager.getConfigDescriptor(config);
        assertFalse(descriptor.getItems().stream().anyMatch(item -> "mouseClick".equals(item.getKey())));
    }

    @Test
    void theIntervalShowsWhileRepeatIsOn() {
        assertFalse(manager.isShown(config, "interval"));
        manager.setConfiguration(ActionTesterConfig.GROUP, "repeat", true);
        assertTrue(manager.isShown(config, "interval"));
    }
}
