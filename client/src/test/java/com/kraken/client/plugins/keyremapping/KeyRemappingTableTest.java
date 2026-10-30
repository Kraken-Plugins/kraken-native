package com.kraken.client.plugins.keyremapping;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.input.KeyRemap;
import com.kraken.client.input.KeyRemapMode;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.ModifierlessKeybind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remapping table Key Remapping builds from its settings, and which mode it picks for the chat's state.
 */
class KeyRemappingTableTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager configManager;
    private KeyRemappingConfig config;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        configManager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        config = configManager.getConfig(KeyRemappingConfig.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static List<KeyRemap> remapsTo(KeyRemapMode mode, int toKeyCode) {
        return mode.remaps().stream().filter(r -> r.toKeyCode() == toKeyCode).toList();
    }

    @Test
    void theDefaultsRemapTheCameraAndLockTypingUntilEnter() {
        List<KeyRemapMode> table = KeyRemappingTable.build(config);
        assertEquals(5, table.size());
        assertEquals(KeyRemapMode.PASS, table.get(KeyRemappingTable.OFF));

        KeyRemapMode locked = table.get(KeyRemappingTable.LOCKED);
        assertTrue(locked.blockTyping());
        assertEquals(List.of(new KeyRemap(KeyEvent.VK_W, 0, true, KeyEvent.VK_UP, KeyRemap.STAY)),
                remapsTo(locked, KeyEvent.VK_UP));
        assertEquals(KeyEvent.VK_S, remapsTo(locked, KeyEvent.VK_DOWN).get(0).keyCode());
        assertEquals(KeyEvent.VK_A, remapsTo(locked, KeyEvent.VK_LEFT).get(0).keyCode());
        assertEquals(KeyEvent.VK_D, remapsTo(locked, KeyEvent.VK_RIGHT).get(0).keyCode());
        assertTrue(remapsTo(locked, KeyEvent.VK_F1).isEmpty(), "F-key remapping is off by default");
        assertTrue(remapsTo(locked, KeyEvent.VK_CONTROL).isEmpty(), "Control mapped to itself makes no rule");
        assertEquals(List.of(KeyRemap.transition(KeyEvent.VK_ENTER, KeyRemappingTable.TYPING)),
                locked.remaps().stream().filter(r -> r.keyCode() == KeyEvent.VK_ENTER).toList());
        assertTrue(locked.remaps().stream().anyMatch(r -> r.keyCode() == KeyEvent.VK_SLASH
                && r.nextMode() == KeyRemappingTable.TYPING));

        KeyRemapMode dialog = table.get(KeyRemappingTable.LOCKED_DIALOG);
        assertEquals(4, remapsTo(dialog, KeyEvent.VK_UP).size() + remapsTo(dialog, KeyEvent.VK_DOWN).size()
                + remapsTo(dialog, KeyEvent.VK_LEFT).size() + remapsTo(dialog, KeyEvent.VK_RIGHT).size());
        assertTrue(remapsTo(dialog, KeyEvent.VK_SPACE).isEmpty(), "Space mapped to itself makes no rule");

        KeyRemapMode typing = table.get(KeyRemappingTable.TYPING);
        assertFalse(typing.blockTyping());
        assertEquals(List.of(KeyRemap.block(KeyEvent.VK_ESCAPE, KeyRemappingTable.LOCKED),
                KeyRemap.transition(KeyEvent.VK_ENTER, KeyRemappingTable.LOCKED)), typing.remaps());
    }

    @Test
    void fKeysAndSpaceFollowTheSettings() {
        configManager.setConfiguration(KeyRemappingConfig.GROUP, "fkeyRemap", true);
        configManager.setConfiguration(KeyRemappingConfig.GROUP, "space", new ModifierlessKeybind(KeyEvent.VK_Q, 0));
        configManager.setConfiguration(KeyRemappingConfig.GROUP, "cameraRemap", false);
        List<KeyRemapMode> table = KeyRemappingTable.build(config);
        KeyRemapMode locked = table.get(KeyRemappingTable.LOCKED);
        assertEquals(KeyEvent.VK_1, remapsTo(locked, KeyEvent.VK_F1).get(0).keyCode());
        assertEquals(KeyEvent.VK_EQUALS, remapsTo(locked, KeyEvent.VK_F12).get(0).keyCode());
        assertTrue(remapsTo(locked, KeyEvent.VK_ESCAPE).isEmpty(), "Escape mapped to itself makes no rule");
        assertTrue(remapsTo(locked, KeyEvent.VK_UP).isEmpty(), "camera remapping off");
        KeyRemapMode dialog = table.get(KeyRemappingTable.LOCKED_DIALOG);
        assertTrue(remapsTo(dialog, KeyEvent.VK_F1).isEmpty(), "no F-keys while a dialog takes numbers");
        assertEquals(KeyEvent.VK_Q, remapsTo(dialog, KeyEvent.VK_SPACE).get(0).keyCode());
        KeyRemapMode options = table.get(KeyRemappingTable.LOCKED_OPTIONS);
        assertTrue(remapsTo(options, KeyEvent.VK_SPACE).isEmpty(), "no Space while an options dialog is open");
    }

    @Test
    void theModeFollowsTheChat() {
        assertEquals(KeyRemappingTable.OFF, KeyRemappingTable.modeFor(false, false, false, false));
        assertEquals(KeyRemappingTable.OFF, KeyRemappingTable.modeFor(false, true, true, true));
        assertEquals(KeyRemappingTable.LOCKED, KeyRemappingTable.modeFor(true, false, false, false));
        assertEquals(KeyRemappingTable.LOCKED_DIALOG, KeyRemappingTable.modeFor(true, false, true, false));
        assertEquals(KeyRemappingTable.LOCKED_OPTIONS, KeyRemappingTable.modeFor(true, false, true, true));
        assertEquals(KeyRemappingTable.TYPING, KeyRemappingTable.modeFor(true, true, true, true));
    }
}
