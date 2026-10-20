package com.kraken.api.eventbus;

import java.util.function.Consumer;

/**
 * One subscription on an {@link EventBus}: a {@link Subscribe} method of a registered object, or a handler passed to
 * {@link EventBus#register(Class, Consumer, float)}. Keep the handle that call returns to
 * {@link EventBus#unregister(Subscriber) unregister} the handler later.
 */
public final class Subscriber {

    private final Class<?> type;
    private final float priority;
    private final Object owner;
    private final Consumer<Object> invoker;
    private final long sequence;
    private final String description;
    private volatile boolean warned;

    Subscriber(Class<?> type, float priority, Object owner, Consumer<Object> invoker, long sequence,
               String description) {
        this.type = type;
        this.priority = priority;
        this.owner = owner;
        this.invoker = invoker;
        this.sequence = sequence;
        this.description = description;
    }

    /**
     * The event type this subscriber takes. It receives events of this type and of its subtypes.
     *
     * @return the subscribed type
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * The subscriber's priority: higher runs first.
     *
     * @return the priority
     */
    public float getPriority() {
        return priority;
    }

    /**
     * The object whose {@link Subscribe} method this is.
     *
     * @return the registered object, or null for a handler registered on its own
     */
    Object owner() {
        return owner;
    }

    /**
     * When the subscription was made, to keep equal priorities in registration order.
     *
     * @return a number that grows with each subscription the bus makes
     */
    long sequence() {
        return sequence;
    }

    /**
     * Calls the subscriber with an event.
     *
     * @param event the event, an instance of {@link #getType()}
     */
    void invoke(Object event) {
        invoker.accept(event);
    }

    /**
     * Marks that this subscriber's failure has been logged, so a subscriber that throws on every event logs once.
     *
     * @return true the first time it's called
     */
    boolean firstFailure() {
        if (warned) {
            return false;
        }
        warned = true;
        return true;
    }

    @Override
    public String toString() {
        return description;
    }
}
