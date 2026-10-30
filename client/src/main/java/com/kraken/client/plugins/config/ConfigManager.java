package com.kraken.client.plugins.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ConfigChanged;
import lombok.extern.java.Log;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Kraken's settings: string values under a group and a key, kept in one
 * properties file ({@code ~/.kraken-native/settings.properties}, keys {@code group.key}).
 *
 * <ul>
 *   <li><b>Typed settings.</b> {@link #getConfig} implements a plugin's {@link Config} interface over the store;
 *       {@link #getConfigDescriptor} lists what it declares, for the settings page.
 *   <li><b>Types.</b> {@code boolean}, {@code int}, {@code long}, {@code float}, {@code double}, {@code String},
 *       enums (stored by name), {@code Color} ({@code #AARRGGBB}; a decimal ARGB integer also reads),
 *       {@code Dimension} ({@code WIDTHxHEIGHT}), {@code Point} ({@code X:Y}), {@code Rectangle}
 *       ({@code X:Y:WIDTH:HEIGHT}; the settings page has no control for it, so it suits hidden items),
 *       {@code Set} of an enum (names, comma-separated), {@link Keybind} and {@link ModifierlessKeybind}
 *       ({@code KEYCODE:MODIFIERS}, as {@link java.awt.event.KeyEvent} codes and {@link java.awt.event.InputEvent}
 *       masks) and {@link Button} (a button on the settings page; nothing is stored).
 *   <li><b>The settings page.</b> {@link ShowIf} shows an item or section only while another item has certain
 *       values ({@link #isShown} says what the page shows), and {@link Suggestions} gives a text item a drop-down
 *       the plugin fills.
 *   <li><b>Events.</b> Every change posts {@link ConfigChanged} on the bus, on the thread that made it.
 *   <li><b>Storage.</b> Changes are written shortly after they're made (several changes in a row are written once),
 *       atomically: to a temporary file that then replaces the settings file. {@link #flush} writes at once.
 * </ul>
 *
 * <p>The plugin manager keeps each plugin's on/off state here too, in group {@code kraken}. Thread-safe.
 */
@Log(topic = "kraken")
@Singleton
public class ConfigManager {

    private static final long SAVE_DELAY_MS = 500;

    private final Path file;
    private final ScheduledExecutorService executor;
    private final EventBus eventBus;
    private final Properties properties = new Properties();
    private ScheduledFuture<?> pendingSave;

    /**
     * Loads the settings file, if there is one.
     *
     * @param file     the settings file
     * @param executor runs the delayed writes
     * @param eventBus where {@link ConfigChanged} is posted
     */
    @Inject
    public ConfigManager(@Named("settings") Path file, ScheduledExecutorService executor, EventBus eventBus) {
        this.file = file;
        this.executor = executor;
        this.eventBus = eventBus;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not read settings from " + file, e);
            }
        }
    }

    /**
     * Implements a config interface over the stored settings: a getter returns the stored value, or the method's
     * {@code default} body when there is none; a setter stores its argument. Plugins usually call this from a
     * {@code @Provides} method, so their config can be injected.
     *
     * @param type the config interface, annotated {@link ConfigGroup}
     * @param <T>  its type
     * @return an implementation reading and writing this manager's settings
     * @throws IllegalArgumentException if {@code type} isn't an interface with {@link ConfigGroup}
     */
    public <T extends Config> T getConfig(Class<T> type) {
        if (!type.isInterface() || type.getAnnotation(ConfigGroup.class) == null) {
            throw new IllegalArgumentException(type.getName() + " isn't a config: it must be an interface with "
                    + "@ConfigGroup");
        }
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new ConfigInvocationHandler(this, type)));
    }

    /**
     * What a config declares: its group, its sections (the {@code String} fields annotated {@link ConfigSection}) and
     * its items (the getters annotated {@link ConfigItem}), each in page order.
     *
     * @param config a config from {@link #getConfig}
     * @return the descriptor
     * @throws IllegalArgumentException if {@code config} implements no interface with {@link ConfigGroup}
     */
    public ConfigDescriptor getConfigDescriptor(Config config) {
        Class<?> type = configInterface(config);
        Comparator<ConfigObject> order = Comparator.comparingInt(ConfigObject::getPosition)
                .thenComparing(ConfigObject::getName);

        List<ConfigSectionDescriptor> sections = new ArrayList<>();
        for (Field field : type.getFields()) {
            ConfigSection section = field.getAnnotation(ConfigSection.class);
            if (section != null && field.getType() == String.class) {
                try {
                    // A plugin's config interface may be package-private
                    field.trySetAccessible();
                    sections.add(new ConfigSectionDescriptor((String) field.get(null), section,
                            List.of(field.getAnnotationsByType(ShowIf.class))));
                } catch (IllegalAccessException e) {
                    log.log(Level.WARNING, "Can't read config section " + field, e);
                }
            }
        }
        sections.sort(order);

        List<ConfigItemDescriptor> items = new ArrayList<>();
        for (Method method : type.getMethods()) {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            if (item != null && method.getParameterCount() == 0) {
                items.add(new ConfigItemDescriptor(item, method.getGenericReturnType(),
                        method.getAnnotation(Range.class), method.getAnnotation(Alpha.class),
                        method.getAnnotation(Units.class), method.getAnnotation(Suggestions.class),
                        List.of(method.getAnnotationsByType(ShowIf.class)), method.getAnnotation(Unsupported.class)));
            }
        }
        items.sort(order);
        ConfigDescriptor descriptor = new ConfigDescriptor(type.getAnnotation(ConfigGroup.class),
                List.copyOf(sections), List.copyOf(items));
        checkConditions(type, descriptor);
        return descriptor;
    }

    /**
     * Whether the settings page shows an item or a section now, by the {@link ShowIf} rules. An item is shown when it
     * isn't hidden, each of its conditions holds on the config's values (stored, else the defaults), the items those
     * conditions name are shown by the same rules (hidden ones included), and its section, if it has one, is shown.
     * A section is shown when its conditions hold the same way and at least one of its items is shown. Conditions
     * that loop back on themselves never hold.
     *
     * @param config     a config from {@link #getConfig}
     * @param descriptor its descriptor
     * @param object     one of the descriptor's items or sections
     * @return true if the page shows it
     */
    public boolean isShown(Config config, ConfigDescriptor descriptor, ConfigObject object) {
        if (object instanceof ConfigSectionDescriptor section) {
            return holds(config, descriptor, section, new HashSet<>())
                    && descriptor.getItems().stream().anyMatch(item -> section.getKey().equals(item.getItem().section())
                    && isShown(config, descriptor, item));
        }
        if (object instanceof ConfigItemDescriptor item && item.getItem().hidden()) {
            return false;
        }
        return holds(config, descriptor, object, new HashSet<>());
    }

    /**
     * Whether the settings page shows an item now, as {@link #isShown(Config, ConfigDescriptor, ConfigObject)}
     * decides.
     *
     * @param config a config from {@link #getConfig}
     * @param key    the item's key
     * @return true if the page shows it; false if the config has no such item
     */
    public boolean isShown(Config config, String key) {
        ConfigDescriptor descriptor = getConfigDescriptor(config);
        ConfigItemDescriptor item = descriptor.item(key);
        return item != null && isShown(config, descriptor, item);
    }

    /**
     * Whether an item's or section's conditions hold, with the items they name and the item's section shown.
     *
     * @param config     the config
     * @param descriptor its descriptor
     * @param object     the item or section
     * @param deciding   the items and sections whose conditions are being worked out, to catch a loop
     * @return true if it is shown, hidden items aside
     */
    private boolean holds(Config config, ConfigDescriptor descriptor, ConfigObject object,
                          Set<ConfigObject> deciding) {
        if (!deciding.add(object)) {
            return false;
        }
        try {
            for (ShowIf condition : object.getShowIf()) {
                ConfigItemDescriptor decider = descriptor.item(condition.key());
                if (decider == null || !holds(config, descriptor, decider, deciding)
                        || !matches(value(config, decider), condition.values())) {
                    return false;
                }
            }
            if (object instanceof ConfigItemDescriptor item && !item.getItem().section().isEmpty()) {
                ConfigSectionDescriptor section = descriptor.section(item.getItem().section());
                return section == null || holds(config, descriptor, section, deciding);
            }
            return true;
        } finally {
            deciding.remove(object);
        }
    }

    /**
     * Whether a value is one of a condition's values: as stored, or for a set, any of its elements.
     *
     * @param value  the value, as the getter returns it
     * @param values the condition's values
     * @return true if it matches
     */
    private boolean matches(Object value, String[] values) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(element -> matches(element, values));
        }
        if (value == null) {
            return false;
        }
        String stored = objectToString(value);
        for (String wanted : values) {
            if (wanted.equals(stored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An item's value, through the config's getter.
     *
     * @param config the config
     * @param item   the item
     * @return the stored value, else the default; null if the getter can't be called
     */
    private static Object value(Config config, ConfigItemDescriptor item) {
        for (Method method : configInterface(config).getMethods()) {
            ConfigItem annotation = method.getAnnotation(ConfigItem.class);
            if (annotation != null && method.getParameterCount() == 0 && annotation.keyName().equals(item.getKey())) {
                try {
                    // A plugin's config interface may be package-private
                    method.trySetAccessible();
                    return method.invoke(config);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Logs the {@link ShowIf} conditions that can never hold as written: those naming no item, and values an enum
     * item has no constant for.
     *
     * @param type       the config interface
     * @param descriptor its descriptor
     */
    private static void checkConditions(Class<?> type, ConfigDescriptor descriptor) {
        List<ConfigObject> objects = new ArrayList<>(descriptor.getSections());
        objects.addAll(descriptor.getItems());
        for (ConfigObject object : objects) {
            for (ShowIf condition : object.getShowIf()) {
                ConfigItemDescriptor decider = descriptor.item(condition.key());
                if (decider == null) {
                    log.warning("Config " + type.getSimpleName() + ": " + object.getKey() + " is shown by "
                            + condition.key() + ", which isn't one of its items");
                    continue;
                }
                Class<?> constants = enumOf(decider.getType());
                if (constants == null) {
                    continue;
                }
                for (String value : condition.values()) {
                    boolean known = Arrays.stream(constants.getEnumConstants())
                            .anyMatch(constant -> ((Enum<?>) constant).name().equals(value));
                    if (!known) {
                        log.warning("Config " + type.getSimpleName() + ": " + object.getKey() + " is shown when "
                                + condition.key() + " is " + value + ", which isn't a " + constants.getSimpleName());
                    }
                }
            }
        }
    }

    /**
     * The enum an item's value is, or is a set of.
     *
     * @param type the item's type
     * @return the enum, or null if the item isn't one
     */
    private static Class<?> enumOf(Type type) {
        if (type instanceof Class<?> clazz && clazz.isEnum()) {
            return clazz;
        }
        if (type instanceof ParameterizedType set && set.getRawType() == Set.class
                && set.getActualTypeArguments()[0] instanceof Class<?> element && element.isEnum()) {
            return element;
        }
        return null;
    }

    /**
     * A setting's value.
     *
     * @param group the group, e.g. a plugin's config group
     * @param key   the key within the group
     * @return the value, or null if it isn't set
     */
    public String getConfiguration(String group, String key) {
        return properties.getProperty(group + "." + key);
    }

    /**
     * A setting's value, converted (see the class description for the types).
     *
     * @param group the group
     * @param key   the key within the group
     * @param type  the type to convert to, e.g. {@code int.class}, or a {@code Set<Skill>} from a method's generic
     *              return type
     * @param <T>   the type
     * @return the value, or null if it isn't set or doesn't convert
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfiguration(String group, String key, Type type) {
        String value = getConfiguration(group, key);
        if (value == null) {
            return null;
        }
        try {
            return (T) stringToObject(value, type);
        } catch (RuntimeException e) {
            log.warning("Setting " + group + "." + key + " = \"" + value + "\" isn't a " + type.getTypeName());
            return null;
        }
    }

    /**
     * Sets a setting and posts {@link ConfigChanged}. Setting it to its current value does nothing.
     *
     * @param group the group
     * @param key   the key within the group
     * @param value the new value; null unsets it
     */
    public void setConfiguration(String group, String key, String value) {
        if (value == null) {
            unsetConfiguration(group, key);
            return;
        }
        String old;
        synchronized (this) {
            old = (String) properties.setProperty(group + "." + key, value);
            if (value.equals(old)) {
                return;
            }
            scheduleSave();
        }
        eventBus.post(new ConfigChanged(group, null, key, old, value));
    }

    /**
     * Sets a setting from a typed value, stored as {@link #objectToString} writes it.
     *
     * @param group the group
     * @param key   the key within the group
     * @param value the new value; null unsets it
     */
    public void setConfiguration(String group, String key, Object value) {
        setConfiguration(group, key, value == null ? null : objectToString(value));
    }

    /**
     * Removes a setting, so it reads as unset (a config getter then returns its default), and posts
     * {@link ConfigChanged} if it was set.
     *
     * @param group the group
     * @param key   the key within the group
     */
    public void unsetConfiguration(String group, String key) {
        String old;
        synchronized (this) {
            old = (String) properties.remove(group + "." + key);
            if (old == null) {
                return;
            }
            scheduleSave();
        }
        eventBus.post(new ConfigChanged(group, null, key, old, null));
    }

    /**
     * The settings whose full key ({@code group.key}) starts with a prefix.
     *
     * @param prefix the start, e.g. {@code "npchighlight."}
     * @return the full keys, sorted
     */
    public List<String> getConfigurationKeys(String prefix) {
        return properties.stringPropertyNames().stream().filter(key -> key.startsWith(prefix)).sorted().toList();
    }

    /**
     * Converts a stored setting to a type (see the class description for the types).
     *
     * @param value the stored text
     * @param type  the type to convert to
     * @return the value
     * @throws IllegalArgumentException if the text doesn't convert, or the type isn't supported
     */
    public Object stringToObject(String value, Type type) {
        Class<?> raw = rawType(type);
        if (raw == String.class) {
            return value;
        }
        String text = value.trim();
        if (raw == boolean.class || raw == Boolean.class) {
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("not true or false");
            }
            return Boolean.parseBoolean(text);
        }
        if (raw == int.class || raw == Integer.class) {
            return Integer.parseInt(text);
        }
        if (raw == long.class || raw == Long.class) {
            return Long.parseLong(text);
        }
        if (raw == float.class || raw == Float.class) {
            return Float.parseFloat(text);
        }
        if (raw == double.class || raw == Double.class) {
            return Double.parseDouble(text);
        }
        if (raw.isEnum()) {
            return enumValue(raw, text);
        }
        if (raw == Color.class) {
            return parseColor(text);
        }
        if (raw == Dimension.class) {
            String[] parts = text.split("x");
            if (parts.length != 2) {
                throw new IllegalArgumentException("not WIDTHxHEIGHT");
            }
            return new Dimension(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        }
        if (raw == Point.class) {
            String[] parts = text.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("not X:Y");
            }
            return new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        }
        if (raw == Rectangle.class) {
            String[] parts = text.split(":");
            if (parts.length != 4) {
                throw new IllegalArgumentException("not X:Y:WIDTH:HEIGHT");
            }
            return new Rectangle(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
        }
        if (raw == Set.class && type instanceof ParameterizedType set
                && set.getActualTypeArguments()[0] instanceof Class<?> element && element.isEnum()) {
            return enumSet(element, text);
        }
        if (raw == Keybind.class || raw == ModifierlessKeybind.class) {
            String[] parts = text.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("not KEYCODE:MODIFIERS");
            }
            int keyCode = Integer.parseInt(parts[0].trim());
            int modifiers = Integer.parseInt(parts[1].trim());
            return raw == ModifierlessKeybind.class ? new ModifierlessKeybind(keyCode, modifiers)
                    : new Keybind(keyCode, modifiers);
        }
        throw new IllegalArgumentException("unsupported setting type " + type.getTypeName());
    }

    /**
     * Turns a value into the text it is stored as: {@code #AARRGGBB} for a colour, {@code WIDTHxHEIGHT} for a
     * dimension, {@code X:Y} for a point, {@code X:Y:WIDTH:HEIGHT} for a rectangle, the name for an enum,
     * {@code KEYCODE:MODIFIERS} for a keybind, comma-separated names for a set; anything else's {@code toString}.
     *
     * @param value the value
     * @return the text to store
     */
    public String objectToString(Object value) {
        if (value instanceof Color color) {
            return String.format(Locale.ROOT, "#%08X", color.getRGB());
        }
        if (value instanceof Dimension dimension) {
            return dimension.width + "x" + dimension.height;
        }
        if (value instanceof Point point) {
            return point.x + ":" + point.y;
        }
        if (value instanceof Rectangle rectangle) {
            return rectangle.x + ":" + rectangle.y + ":" + rectangle.width + ":" + rectangle.height;
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Keybind keybind) {
            return keybind.getKeyCode() + ":" + keybind.getModifiers();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::objectToString).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    /**
     * Writes any change not yet written. The client calls this as it exits.
     */
    public synchronized void flush() {
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
            save();
        }
    }

    private void scheduleSave() {
        if (pendingSave == null) {
            pendingSave = executor.schedule(this::saveScheduled, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void saveScheduled() {
        if (pendingSave != null) {
            pendingSave = null;
            save();
        }
    }

    private void save() {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "settings", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "Kraken settings");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not write settings to " + file, e);
        }
    }

    /**
     * The config interface an object implements.
     *
     * @param config a config proxy
     * @return its interface with {@link ConfigGroup}
     */
    private static Class<?> configInterface(Config config) {
        for (Class<?> type : config.getClass().getInterfaces()) {
            if (type.getAnnotation(ConfigGroup.class) != null) {
                return type;
            }
        }
        throw new IllegalArgumentException(config.getClass().getName() + " implements no @ConfigGroup interface");
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new IllegalArgumentException("unsupported setting type " + type.getTypeName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<?> enumSet(Class<?> element, String text) {
        EnumSet set = EnumSet.noneOf((Class) element);
        for (String name : text.split(",")) {
            if (!name.isBlank()) {
                set.add(enumValue(element, name.trim()));
            }
        }
        return set;
    }

    /**
     * Reads a stored colour: {@code #AARRGGBB}, {@code #RRGGBB} (opaque), or decimal ARGB.
     *
     * @param text the stored text
     * @return the colour
     */
    private static Color parseColor(String text) {
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            }
            if (hex.length() == 8) {
                return new Color((int) Long.parseLong(hex, 16), true);
            }
            throw new IllegalArgumentException("not #RRGGBB or #AARRGGBB");
        }
        return new Color(Integer.parseInt(text), true);
    }
}
