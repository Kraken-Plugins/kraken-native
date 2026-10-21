package com.kraken.api;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The poll loop's wake-up: a native raise starts a loop iteration at once and reads the events, instead of waiting out
 * the loop's sleep or the safety interval of a connected hook.
 */
class GameSessionTest {

    /**
     * The loop's wait when no raise wakes it, longer than any test runs, so only a raise moves it.
     */
    private static final long NO_TIMER_MS = 60_000;

    private static NativeEvent gameTickRaise(long seq) {
        return new NativeEvent("ON_GAME_TICK", 44, new long[4], Map.of(), seq);
    }

    @Test
    void aRaiseWakesTheLoopAndReadsTheGameAtOnce() throws Exception {
        GameFixture game = new GameFixture();
        EventBus bus = new EventBus();
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        bus.register(GameStateChanged.class, seen::add, 0);
        bus.register(GameTick.class, seen::add, 0);

        try (GameSession session = new GameSession(game.memory, game.map, bus, NO_TIMER_MS)) {
            session.setHookConnected(true);
            session.start();
            assertNull(seen.poll(100, TimeUnit.MILLISECONDS), "the loop waits until a raise wakes it");

            session.onNative(gameTickRaise(1));
            assertInstanceOf(GameStateChanged.class, seen.poll(5, TimeUnit.SECONDS),
                    "the raise woke the loop, whose first read replays the game state");

            game.setTick(124);
            session.onNative(gameTickRaise(2));
            assertInstanceOf(GameTick.class, seen.poll(5, TimeUnit.SECONDS),
                    "the next raise reads the new tick at once, inside the hooked safety interval");
        }
    }
}
