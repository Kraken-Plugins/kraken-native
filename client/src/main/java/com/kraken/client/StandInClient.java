package com.kraken.client;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.ProfileType;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@link Client} plugins are given. RuneLite plugins can assume a client exists; Kraken's API attaches after the
 * game boots, or never (an unsupported build). So plugins get this stand-in, which forwards every call to the attached
 * client once there is one, and until then answers as the readers do for a logged-out game: -1, false, "", null,
 * empty collections and arrays, and the game state the connection implies.
 *
 * <p>It's a {@link Proxy} with a default per return type, so it keeps up as {@code Client} grows.
 */
public final class StandInClient {

    private StandInClient() {
    }

    /**
     * Makes a stand-in.
     *
     * @param attached      the attached client, or null while there is none
     * @param detachedState the game state to report while detached ({@link GameState#STARTING} while the game boots,
     *                      {@link GameState#UNKNOWN} once attaching has failed)
     * @return a client that forwards to {@code attached} whenever it returns one
     */
    public static Client create(Supplier<Client> attached, Supplier<GameState> detachedState) {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                new Handler(attached, detachedState));
    }

    /**
     * Forwards to the attached client, or answers with the detached defaults.
     *
     * @param attached      the attached client, or null
     * @param detachedState the game state while detached
     */
    private record Handler(Supplier<Client> attached, Supplier<GameState> detachedState) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "Client (stand-in, " + (attached.get() == null ? "not attached" : "attached") + ")";
                };
            }
            Client target = attached.get();
            if (target != null) {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return detachedValue(method);
        }

        /**
         * What a method returns while no client is attached.
         *
         * @param method a {@link Client} method
         * @return the "nothing to read" value for its return type
         */
        @SuppressWarnings("unchecked")
        private Object detachedValue(Method method) {
            Class<?> type = method.getReturnType();
            if (type == GameState.class) {
                return detachedState.get();
            }
            if (type == ProfileType.class) {
                return ProfileType.STANDARD;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return -1;
            }
            if (type == long.class) {
                return -1L;
            }
            if (type == short.class) {
                return (short) -1;
            }
            if (type == byte.class) {
                return (byte) -1;
            }
            if (type == float.class) {
                return -1f;
            }
            if (type == double.class) {
                return -1d;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == String.class) {
                return "";
            }
            if (type.isArray()) {
                return Array.newInstance(type.getComponentType(), 0);
            }
            if (type == EnumSet.class) {
                return EnumSet.noneOf(enumType(method.getGenericReturnType()));
            }
            if (type == Set.class) {
                return Set.of();
            }
            if (type == Map.class) {
                return Map.of();
            }
            if (type == List.class || type == Collection.class) {
                return List.of();
            }
            if (type == Optional.class) {
                return Optional.empty();
            }
            return null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Class enumType(Type type) {
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element && element.isEnum()) {
                return element;
            }
            throw new IllegalStateException("No enum type in " + type);
        }
    }
}
