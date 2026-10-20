package com.kraken.api.eventbus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link EventBus}: registration by annotation and by handler, priority order, supertype dispatch, failures and
 * changes while posting.
 */
class EventBusTest {

    private EventBus bus;
    private List<String> seen;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        seen = new ArrayList<>();
    }

    interface Spawn {
    }

    record Ping(String text) {
    }

    record ThingSpawned(String name) implements Spawn {
    }

    record OtherSpawned(int id) implements Spawn {
    }

    class Listener {
        @Subscribe
        public void onPing(Ping ping) {
            seen.add("public " + ping.text());
        }

        @Subscribe
        private void onPrivatePing(Ping ping) {
            seen.add("private " + ping.text());
        }

        @Subscribe
        void onSpawn(Spawn spawn) {
            seen.add("spawn " + spawn);
        }
    }

    @Test
    void registeredMethodsOfAnyVisibilityReceiveTheirEvents() {
        Listener listener = new Listener();
        bus.register(listener);
        bus.post(new Ping("a"));
        assertEquals(2, seen.size());
        assertTrue(seen.containsAll(List.of("public a", "private a")));

        seen.clear();
        bus.unregister(listener);
        bus.post(new Ping("b"));
        assertEquals(List.of(), seen);
    }

    @Test
    void aSubscriberToAnInterfaceGetsEveryEventImplementingIt() {
        bus.register(new Listener());
        bus.post(new ThingSpawned("tree"));
        bus.post(new OtherSpawned(7));
        assertEquals(List.of("spawn ThingSpawned[name=tree]", "spawn OtherSpawned[id=7]"), seen);
        assertTrue(bus.hasSubscribers(ThingSpawned.class));
        assertFalse(bus.hasSubscribers(String.class));
    }

    @Test
    void higherPrioritiesRunFirstAndEqualOnesInRegistrationOrder() {
        bus.register(Ping.class, p -> seen.add("low"), -1);
        bus.register(Ping.class, p -> seen.add("first zero"), 0);
        bus.register(Ping.class, p -> seen.add("high"), 5);
        bus.register(Ping.class, p -> seen.add("second zero"), 0);
        bus.register(Object.class, p -> seen.add("everything"), 1);
        bus.post(new Ping("x"));
        assertEquals(List.of("high", "everything", "first zero", "second zero", "low"), seen);
    }

    @Test
    void aThrowingSubscriberDoesNotStopTheOthers() {
        bus.register(Ping.class, p -> {
            throw new IllegalStateException("boom");
        }, 1);
        bus.register(Ping.class, p -> seen.add("after"), 0);
        bus.post(new Ping("x"));
        bus.post(new Ping("y"));
        assertEquals(List.of("after", "after"), seen);
    }

    @Test
    void changesDuringAPostTakeEffectFromTheNextPost() {
        Subscriber[] late = new Subscriber[1];
        Subscriber second = bus.register(Ping.class, p -> seen.add("second " + p.text()), 0);
        bus.register(Ping.class, p -> {
            seen.add("first " + p.text());
            bus.unregister(second);
            if (late[0] == null) {
                late[0] = bus.register(Ping.class, q -> seen.add("late " + q.text()), 0);
            }
        }, 1);
        bus.post(new Ping("a"));
        assertEquals(List.of("first a", "second a"), seen, "the post in progress keeps its subscribers");
        seen.clear();
        bus.post(new Ping("b"));
        assertEquals(List.of("first b", "late b"), seen);
    }

    @Test
    void registeringTwiceSubscribesOnce() {
        Listener listener = new Listener();
        bus.register(listener);
        bus.register(listener);
        bus.post(new OtherSpawned(1));
        assertEquals(1, seen.size());
    }

    static class Base {
        final List<String> calls = new ArrayList<>();

        @Subscribe
        public void onPing(Ping ping) {
            calls.add("base");
        }

        @Subscribe
        private void onBasePing(Ping ping) {
            calls.add("base private");
        }
    }

    static class Derived extends Base {
        @Subscribe
        @Override
        public void onPing(Ping ping) {
            calls.add("derived");
        }
    }

    @Test
    void superclassMethodsAreFoundAndAnOverrideRunsOnce() {
        Derived derived = new Derived();
        bus.register(derived);
        bus.post(new Ping("x"));
        assertEquals(2, derived.calls.size());
        assertTrue(derived.calls.containsAll(List.of("derived", "base private")));
    }

    static class BadArity {
        @Subscribe
        public void onPing(Ping ping, int extra) {
        }
    }

    static class BadReturn {
        @Subscribe
        public String onPing(Ping ping) {
            return "";
        }
    }

    @Test
    void badSubscribeMethodsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> bus.register(new BadArity()));
        assertThrows(IllegalArgumentException.class, () -> bus.register(new BadReturn()));
        assertFalse(bus.hasSubscribers(Ping.class));
    }

    @Test
    void subscriptionListenersHearEachChangedType() {
        List<Class<?>> changes = new ArrayList<>();
        bus.addSubscriptionListener(changes::add);
        Subscriber subscriber = bus.register(Ping.class, p -> {
        }, 0);
        bus.unregister(subscriber);
        bus.unregister(subscriber);
        assertEquals(List.of(Ping.class, Ping.class), changes);
    }
}
