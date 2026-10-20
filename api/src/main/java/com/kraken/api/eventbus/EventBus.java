package com.kraken.api.eventbus;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers events to subscribers, as RuneLite's {@code EventBus}. Objects {@link #register} their {@link Subscribe}
 * methods; anything can {@link #post} an event, and every subscriber to its type runs on the posting thread, highest
 * priority first.
 *
 * <p>The API posts the game events in {@code com.kraken.api.events} on its poll thread (the "client thread"), and only
 * tracks a kind of event while something subscribes to it. The Kraken client posts its own events (plugins changing,
 * shutdown…) on the same bus.
 *
 * <p>Differences from RuneLite's bus:
 * <ul>
 *   <li>An event also reaches subscribers to its supertypes and to the interfaces it implements, so one handler can
 *       take several related events.
 *   <li>Subscriber methods aren't required to be named {@code on<Event>}.
 *   <li>Registering the same object twice is a no-op rather than a double subscription.
 * </ul>
 *
 * <p>Thread-safe. Posting reads an immutable snapshot of the subscriptions, so registering or unregistering while
 * another thread posts is safe; a change takes effect from the next post. A subscriber that throws is logged (once per
 * subscriber) and skipped, and the rest still run.
 */
public final class EventBus {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final Comparator<Subscriber> ORDER = Comparator.comparingDouble(Subscriber::getPriority).reversed()
            .thenComparingLong(Subscriber::sequence);

    private final Object lock = new Object();
    private final List<Consumer<Class<?>>> subscriptionListeners = new CopyOnWriteArrayList<>();
    private volatile Table table = new Table(Map.of());
    private long nextSequence;

    /**
     * Subscribes every {@link Subscribe} method of an object and of its superclasses. A method overridden in a subclass
     * is subscribed once.
     *
     * @param subscriber the object whose methods to subscribe
     * @throws IllegalArgumentException if a {@link Subscribe} method doesn't take exactly one non-primitive parameter,
     *                                  returns a value, or is static; nothing is subscribed then
     */
    public void register(Object subscriber) {
        List<Method> methods = subscribeMethods(subscriber.getClass());
        Set<Class<?>> changed = new LinkedHashSet<>();
        synchronized (lock) {
            if (table.owners.contains(new Identity(subscriber))) {
                return;
            }
            List<Subscriber> added = new ArrayList<>(methods.size());
            for (Method method : methods) {
                Class<?> type = method.getParameterTypes()[0];
                added.add(new Subscriber(type, method.getAnnotation(Subscribe.class).priority(), subscriber,
                        invoker(subscriber, method), nextSequence++, describe(method)));
                changed.add(type);
            }
            if (added.isEmpty()) {
                return;
            }
            table = table.with(added);
        }
        notifyListeners(changed);
    }

    /**
     * Subscribes one handler to an event type, without an object or annotation.
     *
     * @param type     the event type; the handler also receives its subtypes
     * @param handler  what to run for each event
     * @param priority higher runs first among the type's subscribers
     * @param <T>      the event type
     * @return the subscription, for {@link #unregister(Subscriber)}
     */
    @SuppressWarnings("unchecked")
    public <T> Subscriber register(Class<T> type, Consumer<? super T> handler, float priority) {
        Subscriber added;
        synchronized (lock) {
            added = new Subscriber(type, priority, null, (Consumer<Object>) handler, nextSequence++,
                    "handler for " + type.getSimpleName() + " (" + handler.getClass().getName() + ")");
            table = table.with(List.of(added));
        }
        notifyListeners(Set.of(type));
        return added;
    }

    /**
     * Removes every subscription an object's {@link #register} made. An object that isn't registered is ignored.
     *
     * @param subscriber the object passed to {@link #register(Object)}
     */
    public void unregister(Object subscriber) {
        Set<Class<?>> changed = new LinkedHashSet<>();
        synchronized (lock) {
            List<Subscriber> removed = table.all.stream().filter(s -> s.owner() == subscriber).toList();
            if (removed.isEmpty()) {
                return;
            }
            removed.forEach(s -> changed.add(s.getType()));
            table = table.without(removed);
        }
        notifyListeners(changed);
    }

    /**
     * Removes one subscription. Null, or a subscription already removed, is ignored.
     *
     * @param subscriber the handle {@link #register(Class, Consumer, float)} returned
     */
    public void unregister(Subscriber subscriber) {
        if (subscriber == null) {
            return;
        }
        synchronized (lock) {
            if (!table.all.contains(subscriber)) {
                return;
            }
            table = table.without(List.of(subscriber));
        }
        notifyListeners(Set.of(subscriber.getType()));
    }

    /**
     * Runs every subscriber to the event's class, its superclasses and its interfaces, on this thread, highest
     * priority first. A subscriber that throws is logged and the rest still run.
     *
     * @param event the event
     */
    public void post(Object event) {
        for (Subscriber subscriber : table.forEvent(event.getClass())) {
            try {
                subscriber.invoke(event);
            } catch (Exception | LinkageError e) {
                Level level = subscriber.firstFailure() ? Level.WARNING : Level.FINE;
                log.log(level, subscriber + " threw handling " + event.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Whether posting an event of a type would reach anyone: a subscriber to the type, a supertype or an interface it
     * implements. The API's event tracking uses this to only read what someone listens to.
     *
     * @param type the event type
     * @return true if at least one subscriber would receive it
     */
    public boolean hasSubscribers(Class<?> type) {
        return !table.forEvent(type).isEmpty();
    }

    /**
     * Calls a listener after subscriptions change, with each event type gained or lost a subscriber. It runs on the
     * thread that registered or unregistered.
     *
     * @param listener takes each changed event type
     */
    public void addSubscriptionListener(Consumer<Class<?>> listener) {
        subscriptionListeners.add(listener);
    }

    private void notifyListeners(Set<Class<?>> types) {
        for (Consumer<Class<?>> listener : subscriptionListeners) {
            for (Class<?> type : types) {
                try {
                    listener.accept(type);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Subscription listener threw", e);
                }
            }
        }
    }

    /**
     * The {@link Subscribe} methods of a class and its superclasses, checked for a usable signature. A method a
     * subclass overrides with its own {@code @Subscribe} is only listed once.
     *
     * @param clazz the subscriber's class
     * @return its subscribe methods, most-derived class first
     */
    private static List<Method> subscribeMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Subscribe.class)) {
                    continue;
                }
                check(method);
                boolean overridable = !Modifier.isPrivate(method.getModifiers());
                String signature = method.getName() + "(" + method.getParameterTypes()[0].getName() + ")";
                if (overridable && !seen.add(signature)) {
                    continue;
                }
                methods.add(method);
            }
        }
        return methods;
    }

    private static void check(Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException("@Subscribe method " + method + " must take exactly one argument");
        }
        if (method.getParameterTypes()[0].isPrimitive()) {
            throw new IllegalArgumentException("@Subscribe method " + method + " cannot subscribe to a primitive");
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException("@Subscribe method " + method + " cannot return a value");
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("@Subscribe method " + method + " cannot be static");
        }
    }

    /**
     * A fast way to call a subscriber method: a lambda made with {@link LambdaMetafactory}, or a bound method handle
     * if the lambda can't be made (as for a class whose module doesn't open it).
     *
     * @param target the registered object
     * @param method one of its {@link Subscribe} methods
     * @return a consumer that calls the method on the target
     */
    @SuppressWarnings("unchecked")
    private static Consumer<Object> invoker(Object target, Method method) {
        Class<?> declaring = method.getDeclaringClass();
        try {
            MethodHandles.Lookup caller = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
            MethodHandle implementation = caller.unreflect(method);
            CallSite site = LambdaMetafactory.metafactory(caller, "accept",
                    MethodType.methodType(Consumer.class, declaring),
                    MethodType.methodType(void.class, Object.class),
                    implementation,
                    MethodType.methodType(void.class, method.getParameterTypes()[0]));
            return (Consumer<Object>) site.getTarget().bindTo(target).invoke();
        } catch (Throwable e) {
            log.log(Level.FINE, "No lambda for " + method + "; calling it through a method handle", e);
        }
        try {
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method).bindTo(target);
            return event -> {
                try {
                    handle.invoke(event);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new UndeclaredThrowableException(e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("Can't call @Subscribe method " + method, e);
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + "("
                + method.getParameterTypes()[0].getSimpleName() + ")";
    }

    /**
     * A registered object, compared by identity: two equal plugins are still two subscribers.
     *
     * @param object the registered object
     */
    private record Identity(Object object) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && identity.object == object;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object);
        }
    }

    /**
     * An immutable set of subscriptions, with each event class's dispatch list worked out on first post and kept.
     */
    private static final class Table {
        final List<Subscriber> all;
        final Set<Identity> owners;
        final Map<Class<?>, List<Subscriber>> byType;
        final Map<Class<?>, List<Subscriber>> dispatch = new ConcurrentHashMap<>();

        Table(Map<Class<?>, List<Subscriber>> byType) {
            this.byType = byType;
            List<Subscriber> everything = new ArrayList<>();
            Set<Identity> registered = new HashSet<>();
            for (List<Subscriber> subscribers : byType.values()) {
                for (Subscriber subscriber : subscribers) {
                    everything.add(subscriber);
                    if (subscriber.owner() != null) {
                        registered.add(new Identity(subscriber.owner()));
                    }
                }
            }
            this.all = List.copyOf(everything);
            this.owners = Set.copyOf(registered);
        }

        Table with(List<Subscriber> added) {
            Map<Class<?>, List<Subscriber>> next = copy();
            for (Subscriber subscriber : added) {
                next.computeIfAbsent(subscriber.getType(), t -> new ArrayList<>()).add(subscriber);
            }
            return new Table(freeze(next));
        }

        Table without(List<Subscriber> removed) {
            Map<Class<?>, List<Subscriber>> next = copy();
            for (Subscriber subscriber : removed) {
                List<Subscriber> list = next.get(subscriber.getType());
                if (list != null) {
                    list.remove(subscriber);
                    if (list.isEmpty()) {
                        next.remove(subscriber.getType());
                    }
                }
            }
            return new Table(freeze(next));
        }

        List<Subscriber> forEvent(Class<?> eventType) {
            return dispatch.computeIfAbsent(eventType, this::resolve);
        }

        private List<Subscriber> resolve(Class<?> eventType) {
            List<Subscriber> subscribers = new ArrayList<>();
            for (Class<?> type : supertypes(eventType)) {
                subscribers.addAll(byType.getOrDefault(type, List.of()));
            }
            subscribers.sort(ORDER);
            return List.copyOf(subscribers);
        }

        private Map<Class<?>, List<Subscriber>> copy() {
            Map<Class<?>, List<Subscriber>> next = new HashMap<>();
            byType.forEach((type, list) -> next.put(type, new ArrayList<>(list)));
            return next;
        }

        private static Map<Class<?>, List<Subscriber>> freeze(Map<Class<?>, List<Subscriber>> map) {
            Map<Class<?>, List<Subscriber>> frozen = new HashMap<>();
            map.forEach((type, list) -> frozen.put(type, List.copyOf(list)));
            return Map.copyOf(frozen);
        }

        /**
         * A class, its superclasses and every interface any of them implements.
         *
         * @param type the class
         * @return the types an event of this class can be delivered as
         */
        private static Set<Class<?>> supertypes(Class<?> type) {
            Set<Class<?>> types = new LinkedHashSet<>();
            Deque<Class<?>> pending = new ArrayDeque<>();
            pending.add(type);
            while (!pending.isEmpty()) {
                Class<?> next = pending.poll();
                if (next == null || !types.add(next)) {
                    continue;
                }
                if (next.getSuperclass() != null) {
                    pending.add(next.getSuperclass());
                }
                pending.addAll(List.of(next.getInterfaces()));
            }
            return types;
        }
    }
}
