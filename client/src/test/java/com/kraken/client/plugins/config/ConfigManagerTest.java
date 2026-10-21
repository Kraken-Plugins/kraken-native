package com.kraken.client.plugins.config;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ConfigChanged;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConfigManager}: the settings file (values survive a restart; writes are delayed and atomic), config
 * proxies (defaults, stored values of each type, setters), {@link ConfigChanged}, and descriptors.
 */
class ConfigManagerTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private EventBus bus;
    private Path file;
    private ConfigManager manager;
    private final List<ConfigChanged> changes = new ArrayList<>();

    enum Shape {
        SQUARE, CIRCLE, TRIANGLE
    }

    @ConfigGroup("example")
    interface ExampleConfig extends Config {

        @ConfigSection(name = "Looks", description = "How it looks", position = 5)
        String looks = "looks";

        @ConfigSection(name = "Advanced", description = "", position = 1, closedByDefault = true)
        String advanced = "advanced";

        @ConfigItem(keyName = "greeting", name = "Greeting", description = "What to say", position = 2)
        default String greeting() {
            return "Hello";
        }

        @Range(min = 1, max = 9)
        @Units(Units.TICKS)
        @ConfigItem(keyName = "count", name = "Count", description = "", position = 1)
        default int count() {
            return 3;
        }

        @ConfigItem(keyName = "count", name = "", description = "")
        void setCount(int count);

        @ConfigItem(keyName = "big", name = "Big", description = "", position = 3, section = advanced)
        default long big() {
            return 1L << 40;
        }

        @ConfigItem(keyName = "ratio", name = "Ratio", description = "", position = 3, section = advanced)
        default double ratio() {
            return 0.5;
        }

        @ConfigItem(keyName = "on", name = "On", description = "", position = 0)
        default boolean on() {
            return true;
        }

        @ConfigItem(keyName = "shape", name = "Shape", description = "", position = 0, section = looks)
        default Shape shape() {
            return Shape.SQUARE;
        }

        @Alpha
        @ConfigItem(keyName = "colour", name = "Colour", description = "", position = 1, section = looks)
        default Color colour() {
            return new Color(1, 2, 3, 4);
        }

        @ConfigItem(keyName = "size", name = "Size", description = "", position = 4)
        default Dimension size() {
            return new Dimension(5, 6);
        }

        @ConfigItem(keyName = "shapes", name = "Shapes", description = "", position = 5)
        default Set<Shape> shapes() {
            return EnumSet.of(Shape.CIRCLE);
        }

        @ConfigItem(keyName = "nothing", name = "Nothing", description = "", position = 6)
        String nothing();

        @ConfigItem(keyName = "state", name = "State", description = "", hidden = true)
        default int state() {
            return 0;
        }

        default String shout() {
            return greeting().toUpperCase();
        }
    }

    interface NotAConfig extends Config {
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        bus = new EventBus();
        bus.register(ConfigChanged.class, changes::add, 0);
        file = dir.resolve("nested").resolve("settings.properties");
        manager = newManager();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private ConfigManager newManager() {
        return new ConfigManager(file, scheduler, bus);
    }

    @Test
    void valuesSurviveARestart() {
        assertNull(manager.getConfiguration("kraken", "tickcounterplugin"));
        manager.setConfiguration("kraken", "tickcounterplugin", "true");
        manager.setConfiguration("npchighlight", "names", "Goblin, Guard");
        manager.setConfiguration("npchighlight", "gone", "x");
        manager.unsetConfiguration("npchighlight", "gone");
        assertEquals("true", manager.getConfiguration("kraken", "tickcounterplugin"));
        manager.flush();

        ConfigManager next = newManager();
        assertEquals("true", next.getConfiguration("kraken", "tickcounterplugin"));
        assertEquals("Goblin, Guard", next.getConfiguration("npchighlight", "names"));
        assertNull(next.getConfiguration("npchighlight", "gone"));
    }

    @Test
    void changesAreWrittenShortlyWithoutAFlush() throws Exception {
        manager.setConfiguration("kraken", "a", "1");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("1", newManager().getConfiguration("kraken", "a"));
    }

    @Test
    void writesLeaveNoTemporaryFiles() throws IOException {
        manager.setConfiguration("kraken", "a", "1");
        manager.flush();
        manager.setConfiguration("kraken", "a", "2");
        manager.flush();
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
        assertTrue(Files.readString(file).contains("kraken.a=2"));
    }

    @Test
    void gettersReturnTheInterfacesDefaultsUntilSomethingIsStored() {
        ExampleConfig config = manager.getConfig(ExampleConfig.class);
        assertEquals("Hello", config.greeting());
        assertEquals(3, config.count());
        assertEquals(1L << 40, config.big());
        assertEquals(0.5, config.ratio());
        assertTrue(config.on());
        assertEquals(Shape.SQUARE, config.shape());
        assertEquals(new Color(1, 2, 3, 4), config.colour());
        assertEquals(new Dimension(5, 6), config.size());
        assertEquals(EnumSet.of(Shape.CIRCLE), config.shapes());
        assertNull(config.nothing(), "no default and nothing stored");
        assertEquals("HELLO", config.shout(), "a default method without @ConfigItem runs as written");
    }

    @Test
    void storedValuesConvertToEachType() {
        manager.setConfiguration("example", "greeting", "Hi");
        manager.setConfiguration("example", "count", "7");
        manager.setConfiguration("example", "big", "123456789012");
        manager.setConfiguration("example", "ratio", "1.25");
        manager.setConfiguration("example", "on", "false");
        manager.setConfiguration("example", "shape", "TRIANGLE");
        manager.setConfiguration("example", "colour", "#80FF0000");
        manager.setConfiguration("example", "size", "10x20");
        manager.setConfiguration("example", "shapes", "SQUARE,TRIANGLE");
        manager.setConfiguration("example", "nothing", "something");

        ExampleConfig config = manager.getConfig(ExampleConfig.class);
        assertEquals("Hi", config.greeting());
        assertEquals(7, config.count());
        assertEquals(123456789012L, config.big());
        assertEquals(1.25, config.ratio());
        assertEquals(false, config.on());
        assertEquals(Shape.TRIANGLE, config.shape());
        assertEquals(new Color(255, 0, 0, 128), config.colour());
        assertEquals(new Dimension(10, 20), config.size());
        assertEquals(EnumSet.of(Shape.SQUARE, Shape.TRIANGLE), config.shapes());
        assertEquals("something", config.nothing());

        manager.setConfiguration("example", "colour", "#00FF00");
        assertEquals(new Color(0, 255, 0), config.colour(), "#RRGGBB is opaque");
        manager.setConfiguration("example", "colour", String.valueOf(new Color(1, 2, 3, 4).getRGB()));
        assertEquals(new Color(1, 2, 3, 4), config.colour(), "decimal form");
        manager.setConfiguration("example", "shapes", "");
        assertEquals(EnumSet.noneOf(Shape.class), config.shapes());
    }

    @Test
    void aStoredValueThatDoesNotConvertGivesTheDefault() {
        manager.setConfiguration("example", "count", "lots");
        manager.setConfiguration("example", "on", "yes");
        manager.setConfiguration("example", "shape", "HEXAGON");
        ExampleConfig config = manager.getConfig(ExampleConfig.class);
        assertEquals(3, config.count());
        assertTrue(config.on());
        assertEquals(Shape.SQUARE, config.shape());
    }

    @Test
    void typedValuesRoundTrip() {
        manager.setConfiguration("example", "colour", new Color(10, 20, 30, 40));
        manager.setConfiguration("example", "size", new Dimension(3, 4));
        manager.setConfiguration("example", "shapes", EnumSet.of(Shape.CIRCLE, Shape.TRIANGLE));
        manager.setConfiguration("example", "shape", Shape.CIRCLE);
        assertEquals("#280A141E", manager.getConfiguration("example", "colour"));
        assertEquals("3x4", manager.getConfiguration("example", "size"));
        assertEquals("CIRCLE,TRIANGLE", manager.getConfiguration("example", "shapes"));
        assertEquals(new Color(10, 20, 30, 40), manager.getConfiguration("example", "colour", Color.class));
        assertEquals(Shape.CIRCLE, manager.getConfiguration("example", "shape", Shape.class));
        assertNull(manager.getConfiguration("example", "missing", int.class));
        manager.setConfiguration("example", "count", "x");
        assertNull(manager.getConfiguration("example", "count", int.class), "doesn't convert");
    }

    @Test
    void rectanglesStoreAsXYWidthHeight() {
        manager.setConfiguration("example", "bounds", new Rectangle(-1920, 40, 800, 600));
        assertEquals("-1920:40:800:600", manager.getConfiguration("example", "bounds"));
        assertEquals(new Rectangle(-1920, 40, 800, 600),
                manager.getConfiguration("example", "bounds", Rectangle.class));
        manager.setConfiguration("example", "bounds", "1:2:3");
        assertNull(manager.getConfiguration("example", "bounds", Rectangle.class), "three parts");
    }

    @Test
    void settersStoreAndEveryChangePostsConfigChanged() {
        ExampleConfig config = manager.getConfig(ExampleConfig.class);
        config.setCount(7);
        assertEquals("7", manager.getConfiguration("example", "count"));
        assertEquals(7, config.count());
        config.setCount(7);
        manager.unsetConfiguration("example", "count");
        manager.unsetConfiguration("example", "count");
        assertEquals(3, config.count(), "unset reads as the default");

        assertEquals(2, changes.size(), "setting the same value, or unsetting twice, posts nothing");
        assertEquals(new ConfigChanged("example", null, "count", null, "7"), changes.get(0));
        assertEquals(new ConfigChanged("example", null, "count", "7", null), changes.get(1));
    }

    @Test
    void theDescriptorListsSectionsAndItemsInPageOrder() {
        ConfigDescriptor descriptor = manager.getConfigDescriptor(manager.getConfig(ExampleConfig.class));
        assertEquals("example", descriptor.getGroup().value());
        assertEquals(List.of("advanced", "looks"),
                descriptor.getSections().stream().map(ConfigSectionDescriptor::getKey).toList());
        assertTrue(descriptor.getSections().get(0).getSection().closedByDefault());
        // By position, then name; the setter isn't an item; the hidden one is
        assertEquals(List.of("state", "on", "shape", "colour", "count", "greeting", "big", "ratio", "size", "shapes",
                        "nothing"),
                descriptor.getItems().stream().map(ConfigItemDescriptor::getKey).toList());
        ConfigItemDescriptor count = descriptor.getItems().get(4);
        assertEquals(int.class, count.getType());
        assertEquals(9, count.getRange().max());
        assertEquals(Units.TICKS, count.getUnits().value());
        assertNotNull(descriptor.getItems().get(3).getAlpha());
        assertEquals("java.util.Set<com.kraken.client.plugins.config.ConfigManagerTest$Shape>",
                descriptor.getItems().get(9).getType().getTypeName());
    }

    @Test
    void onlyAnnotatedInterfacesAreConfigs() {
        assertThrows(IllegalArgumentException.class, () -> manager.getConfig(NotAConfig.class));
    }

    @Test
    void keysCanBeListedByPrefix() {
        manager.setConfiguration("example", "b", "1");
        manager.setConfiguration("example", "a", "1");
        manager.setConfiguration("other", "a", "1");
        assertEquals(List.of("example.a", "example.b"), manager.getConfigurationKeys("example."));
    }
}
