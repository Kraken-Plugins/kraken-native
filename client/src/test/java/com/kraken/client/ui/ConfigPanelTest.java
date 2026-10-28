package com.kraken.client.ui;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.KrakenConfig;
import com.kraken.client.events.ConfigButtonClicked;
import com.kraken.client.events.ConfigSuggestionsRequested;
import com.kraken.client.plugins.config.Button;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.ShowIf;
import com.kraken.client.plugins.config.Suggestions;
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
 * The generated settings page, headless: one control per shown item showing the current value, using a control
 * stores the value, items come and go with their conditions, buttons post their clicks and suggestion drop-downs show
 * what the plugin answers.
 */
class ConfigPanelTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
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

    @ConfigGroup("dynamic")
    interface DynamicConfig extends Config {

        @ConfigSection(name = "Advanced", description = "", position = 10)
        String advanced = "advanced";

        @ConfigItem(keyName = "mode", name = "Mode", description = "", position = 0)
        default Mode mode() {
            return Mode.SLOW;
        }

        @ShowIf(key = "mode", values = {"FAST"})
        @ConfigItem(keyName = "boost", name = "Boost", description = "", position = 1)
        default boolean boost() {
            return false;
        }

        @ShowIf(key = "boost")
        @ConfigItem(keyName = "power", name = "Power", description = "", position = 2)
        default int power() {
            return 3;
        }

        @Suggestions
        @ConfigItem(keyName = "target", name = "Target", description = "", position = 3)
        default String target() {
            return "Goblin";
        }

        @ConfigItem(keyName = "at", name = "At", description = "", position = 4)
        default Point at() {
            return new Point(5, 6);
        }

        @ConfigItem(keyName = "go", name = "Go", description = "Runs it", position = 5)
        Button go();

        @ShowIf(key = "mode", values = {"FAST"})
        @ConfigItem(keyName = "detail", name = "Detail", description = "", position = 11, section = advanced)
        default boolean detail() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, bus);
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

    /**
     * Whether a labelled row is shown: its label and everything above it up to the page are visible.
     *
     * @param page the page
     * @param text the label's text, without the markup the label wraps it in
     * @return true if it shows
     */
    private static boolean shown(Container page, String text) {
        Component at = find(page, JLabel.class).stream()
                .filter(label -> text.equals(label.getText().replaceAll("<[^>]*>", "").trim())).findFirst()
                .orElseThrow(() -> new AssertionError("no label " + text));
        for (; at != null && at != page; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private ConfigPanel page(Config config) throws Exception {
        ConfigPanel[] panel = new ConfigPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new ConfigPanel(manager, bus, config, "Dynamic", () -> {
        }));
        return panel[0];
    }

    @Test
    void rowsAndSectionsComeAndGoWithTheirConditions() throws Exception {
        ConfigPanel page = page(manager.getConfig(DynamicConfig.class));
        assertTrue(shown(page, "Mode"));
        assertFalse(shown(page, "Boost"), "mode is SLOW");
        assertFalse(shown(page, "Power"));
        assertFalse(shown(page, "Advanced"), "its only item is hidden");

        JComboBox<?> mode = find(page, JComboBox.class).get(0);
        SwingUtilities.invokeAndWait(() -> mode.setSelectedItem(Mode.FAST));
        assertTrue(shown(page, "Boost"), "picking FAST shows it at once");
        assertFalse(shown(page, "Power"), "boost is off");
        assertTrue(shown(page, "Advanced"));

        // The page hears changes made elsewhere while it is in a window
        SwingUtilities.invokeAndWait(page::addNotify);
        manager.setConfiguration("dynamic", "boost", true);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertTrue(shown(page, "Power"), "a change made elsewhere shows it too");

        SwingUtilities.invokeAndWait(() -> mode.setSelectedItem(Mode.SLOW));
        assertFalse(shown(page, "Boost"));
        assertFalse(shown(page, "Power"), "boost is on but not shown");
    }

    @Test
    void aButtonPostsItsClickAndStoresNothing() throws Exception {
        List<ConfigButtonClicked> clicks = new ArrayList<>();
        bus.register(ConfigButtonClicked.class, clicks::add, 0);
        ConfigPanel page = page(manager.getConfig(DynamicConfig.class));
        JButton go = find(page, JButton.class).stream().filter(b -> "Go".equals(b.getText())).findFirst()
                .orElseThrow();
        SwingUtilities.invokeAndWait(go::doClick);
        assertEquals(List.of(new ConfigButtonClicked("dynamic", "go")), clicks);
        assertNull(manager.getConfiguration("dynamic", "go"));
    }

    @Test
    void suggestionsAreAskedForAsTheDropDownOpensAndAPickIsStored() throws Exception {
        List<String> asked = new ArrayList<>();
        bus.register(ConfigSuggestionsRequested.class, request -> {
            asked.add(request.getGroup() + "." + request.getKey());
            request.suggest(List.of("Goblin", "Guard", "Moss giant"));
        }, 0);
        ConfigPanel page = page(manager.getConfig(DynamicConfig.class));
        JComboBox<?> target = find(page, JComboBox.class).stream().filter(JComboBox::isEditable).findFirst()
                .orElseThrow();
        JTextField field = (JTextField) target.getEditor().getEditorComponent();
        assertEquals("Goblin", field.getText(), "the field shows the value");
        assertEquals(0, target.getItemCount(), "nothing is suggested before the drop-down opens");

        SwingUtilities.invokeAndWait(target::firePopupMenuWillBecomeVisible);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals(List.of("dynamic.target"), asked);
        assertEquals(3, target.getItemCount());
        assertEquals("Goblin", field.getText(), "filling the list keeps the text");
        assertNull(manager.getConfiguration("dynamic", "target"), "and stores nothing");

        SwingUtilities.invokeAndWait(() -> target.setSelectedItem("Moss giant"));
        assertEquals("Moss giant", manager.getConfiguration("dynamic", "target"), "a pick is stored at once");
        assertEquals("Moss giant", field.getText());
    }

    @Test
    void aPointHasTwoSpinners() throws Exception {
        ConfigPanel page = page(manager.getConfig(DynamicConfig.class));
        List<JSpinner> spinners = find(page, JSpinner.class);
        assertEquals(List.of(3, 5, 6), spinners.stream().map(JSpinner::getValue).toList(), "power, then the point");
        SwingUtilities.invokeAndWait(() -> spinners.get(2).setValue(9));
        assertEquals("5:9", manager.getConfiguration("dynamic", "at"));
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
