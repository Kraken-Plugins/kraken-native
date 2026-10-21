package com.kraken.client.ui;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.KrakenConfig;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated settings page, headless: one control per shown item showing the current value, and using a control
 * stores the value.
 */
class ConfigPanelTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager manager;

    enum Mode {
        FAST, SLOW
    }

    @ConfigGroup("paneltest")
    interface PanelConfig extends Config {

        @ConfigSection(name = "More", description = "", position = 10)
        String more = "more";

        @ConfigItem(keyName = "on", name = "On", description = "A switch", position = 0)
        default boolean on() {
            return true;
        }

        @Range(min = 1, max = 9)
        @Units(Units.TICKS)
        @ConfigItem(keyName = "count", name = "Count", description = "", position = 1)
        default int count() {
            return 3;
        }

        @ConfigItem(keyName = "mode", name = "Mode", description = "", position = 2)
        default Mode mode() {
            return Mode.SLOW;
        }

        @ConfigItem(keyName = "note", name = "Note", description = "", position = 3)
        default String note() {
            return "hi";
        }

        @ConfigItem(keyName = "modes", name = "Modes", description = "", position = 11, section = more)
        default Set<Mode> modes() {
            return EnumSet.noneOf(Mode.class);
        }

        @ConfigItem(keyName = "colour", name = "Colour", description = "", position = 12, section = more)
        default Color colour() {
            return Color.RED;
        }

        @ConfigItem(keyName = "size", name = "Size", description = "", position = 13, section = more)
        default Dimension size() {
            return new Dimension(3, 4);
        }

        @ConfigItem(keyName = "ratio", name = "Ratio", description = "", position = 14, section = more)
        default double ratio() {
            return 0.5;
        }

        @ConfigItem(keyName = "state", name = "State", description = "", hidden = true)
        default String state() {
            return "";
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static <T extends Component> List<T> find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(find(container, type));
            }
        }
        return found;
    }

    private static List<String> labels(Container root) {
        return find(root, JLabel.class).stream().map(JLabel::getText).toList();
    }

    @Test
    void controlsShowTheValuesAndStoreWhatTheUserPicks() throws Exception {
        manager.setConfiguration("paneltest", "count", "7");
        ConfigPanel[] panel = new ConfigPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new ConfigPanel(manager, new EventBus(),
                manager.getConfig(PanelConfig.class), "Panel test", () -> {
        }));
        ConfigPanel page = panel[0];

        String all = String.join("|", labels(page));
        assertTrue(all.contains("Count") && all.contains("More") && all.contains("Ratio"), all);
        assertFalse(all.contains("State"), "hidden items aren't shown: " + all);

        List<JSpinner> spinners = find(page, JSpinner.class);
        assertEquals(4, spinners.size(), "count, ratio, and the size's two");
        JSpinner count = spinners.get(0);
        assertEquals(7, count.getValue());
        JComboBox<?> mode = find(page, JComboBox.class).get(0);
        assertEquals(Mode.SLOW, mode.getSelectedItem());
        JTextArea note = find(page, JTextArea.class).get(0);
        assertEquals("hi", note.getText());

        SwingUtilities.invokeAndWait(() -> {
            find(page, JToggleButton.class).stream().filter(b -> !(b instanceof JCheckBox)).findFirst()
                    .orElseThrow().doClick();
            count.setValue(5);
            mode.setSelectedItem(Mode.FAST);
            find(page, JCheckBox.class).get(1).doClick();
            note.setText("typed");
        });
        assertEquals("false", manager.getConfiguration("paneltest", "on"));
        assertEquals("5", manager.getConfiguration("paneltest", "count"));
        assertEquals("FAST", manager.getConfiguration("paneltest", "mode"));
        assertEquals("SLOW", manager.getConfiguration("paneltest", "modes"));
        assertNull(manager.getConfiguration("paneltest", "note"), "text is saved after a pause in typing");

        long deadline = System.currentTimeMillis() + 5_000;
        while (manager.getConfiguration("paneltest", "note") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("typed", manager.getConfiguration("paneltest", "note"));
    }

    @Test
    void krakensOwnSettingsBuildAPage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigPanel page = new ConfigPanel(manager, new EventBus(), manager.getConfig(KrakenConfig.class),
                    "Settings", null);
            String all = String.join("|", labels(page));
            for (String shown : List.of("Window", "Frame rate", "Overlays", "Game size", "Show FPS")) {
                assertTrue(all.contains(shown), shown + " in " + all);
            }
            assertFalse(all.contains("Window bounds"), "hidden items stay off the page: " + all);
            List<JSpinner> spinners = find(page, JSpinner.class);
            assertEquals(List.of(765, 503, 20, 30, 12), spinners.stream().map(JSpinner::getValue).toList(),
                    "game width and height, unfocused frame rate, overlay frame rate, font size");
            assertEquals(240, ((SpinnerNumberModel) spinners.get(0).getModel()).getMinimum(),
                    "the game size's @Range bounds each side");
        });
    }
}
