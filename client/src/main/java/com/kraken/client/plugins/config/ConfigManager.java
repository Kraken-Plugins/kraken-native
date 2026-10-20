package com.kraken.client.plugins.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.events.ConfigChanged;
import lombok.extern.java.Log;

import java.awt.Color;
import java.awt.Dimension;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
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
 *       {@code Dimension} ({@code WIDTHxHEIGHT}) and {@code Set} of an enum (names, comma-separated). Keybinds aren't
 *       supported: Kraken never sees the game's input.
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
                    sections.add(new ConfigSectionDescriptor((String) field.get(null), section));
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
                        method.getAnnotation(Units.class)));
            }
        }
        items.sort(order);
        return new ConfigDescriptor(type.getAnnotation(ConfigGroup.class), List.copyOf(sections),
                List.copyOf(items));
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
        if (raw == Set.class && type instanceof ParameterizedType set
                && set.getActualTypeArguments()[0] instanceof Class<?> element && element.isEnum()) {
            return enumSet(element, text);
        }
        throw new IllegalArgumentException("unsupported setting type " + type.getTypeName());
    }

    /**
     * Turns a value into the text it is stored as: {@code #AARRGGBB} for a colour, {@code WIDTHxHEIGHT} for a
     * dimension, the name for an enum, comma-separated names for a set; anything else's {@code toString}.
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
        if (value instanceof Enum<?> constant) {
            return constant.name();
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
