package com.kraken.client.plugins.config;

import lombok.extern.java.Log;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements a {@link Config} interface over the {@link ConfigManager}. A getter returns the stored value converted
 * to its return type, or the interface's {@code default} body when nothing is stored (or the stored text doesn't
 * convert); a one-argument {@code void} method sets the value, null unsetting it. A {@link Button} getter returns
 * {@link Button#INSTANCE} and reads nothing. A {@code default} method without {@link ConfigItem} is a helper and runs as
 * written.
 *
 * <p>Values aren't cached: each call reads the settings, which is a map lookup and a parse.
 */
@Log(topic = "kraken")
final class ConfigInvocationHandler implements InvocationHandler {

    private static final Object[] NO_ARGS = {};

    private final ConfigManager manager;
    private final Class<?> type;
    private final String group;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the handler for one proxy.
     *
     * @param manager where the values are stored
     * @param type    the config interface
     */
    ConfigInvocationHandler(ConfigManager manager, Class<?> type) {
        this.manager = manager;
        this.type = type;
        this.group = type.getAnnotation(ConfigGroup.class).value();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> type.getSimpleName() + " (config group " + group + ")";
            };
        }
        Object[] arguments = args == null ? NO_ARGS : args;
        ConfigItem item = method.getAnnotation(ConfigItem.class);
        if (item == null) {
            if (method.isDefault()) {
                return callDefault(proxy, method, arguments);
            }
            warnOnce(method, "has no @ConfigItem");
            return zero(method.getReturnType());
        }
        if (arguments.length == 0) {
            return get(proxy, method, item);
        }
        if (arguments.length == 1 && method.getReturnType() == void.class) {
            Object value = arguments[0];
            if (value == null) {
                manager.unsetConfiguration(group, item.keyName());
            } else {
                manager.setConfiguration(group, item.keyName(), manager.objectToString(value));
            }
            return null;
        }
        throw new UnsupportedOperationException(method + " is neither a config getter nor a setter");
    }

    /**
     * A getter's value.
     *
     * @param proxy  the config proxy
     * @param method the getter
     * @param item   its annotation
     * @return the stored value, else the default
     * @throws Throwable if the default method throws
     */
    private Object get(Object proxy, Method method, ConfigItem item) throws Throwable {
        if (method.getReturnType() == Button.class) {
            return Button.INSTANCE;
        }
        String stored = manager.getConfiguration(group, item.keyName());
        if (stored != null) {
            try {
                return manager.stringToObject(stored, method.getGenericReturnType());
            } catch (RuntimeException e) {
                warnOnce(method, "can't use its stored value \"" + stored + "\" (" + e.getMessage()
                        + "), so returns its default");
            }
        }
        if (method.isDefault()) {
            return callDefault(proxy, method, NO_ARGS);
        }
        return zero(method.getReturnType());
    }

    /**
     * Runs a config interface's {@code default} body. {@link InvocationHandler#invokeDefault} refuses interfaces this
     * package can't see (a plugin's package-private config), so those go through a private lookup instead.
     *
     * @param proxy  the config proxy
     * @param method the default method
     * @param args   its arguments
     * @return what it returns
     * @throws Throwable whatever it throws
     */
    private static Object callDefault(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return InvocationHandler.invokeDefault(proxy, method, args);
        } catch (IllegalAccessException e) {
            Class<?> declaring = method.getDeclaringClass();
            return MethodHandles.privateLookupIn(declaring, MethodHandles.lookup())
                    .unreflectSpecial(method, declaring)
                    .bindTo(proxy)
                    .invokeWithArguments(args);
        }
    }

    private void warnOnce(Method method, String problem) {
        if (warned.add(method.getName() + problem)) {
            log.warning("Config " + type.getSimpleName() + "." + method.getName() + " " + problem);
        }
    }

    /**
     * What a method with nothing to return gives: null, or a primitive's zero so unboxing doesn't throw.
     *
     * @param type the return type
     * @return the zero value
     */
    private static Object zero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
