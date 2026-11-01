package com.kraken.api;

import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.MenuOptionClicked;
import com.kraken.api.events.ScriptPostFired;
import com.kraken.api.internal.ScriptStack;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.internal.menu.MenuClick;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The poll loop's wake-up: a native raise starts a loop iteration at once and reads the events, instead of waiting out
 * the loop's sleep or the safety interval of a connected hook.
 */
class GameSessionTest {

    /**
     * The loop's wait when no raise wakes it, and its safety interval between event reads while hooked, longer than any
     * test runs, so only a raise moves the loop and, where a test sets both, only a raise reads the game.
     */
    private static final long NO_TIMER_MS = 60_000;

    private static NativeEvent gameTickRaise(long seq) {
        return new NativeEvent("ON_GAME_TICK", 44, new long[4], Map.of(), seq);
    }

    @Test
    void aStageThatThrowsALinkageErrorDoesNotStopTheLoop() throws Exception {
        GameFixture game = new GameFixture();
        BlockingQueue<Integer> passes = new LinkedBlockingQueue<>();
        int[] count = {0};
        try (GameSession session = new GameSession(game.memory, game.map, new EventBus(), 10)) {
            session.setFrameListener(() -> {
                passes.add(++count[0]);
                if (count[0] == 1) {
                    throw new NoClassDefFoundError("a class a plugin was built against");
                }
            });
            session.start();
            assertEquals(1, passes.poll(5, TimeUnit.SECONDS));
            assertEquals(2, passes.poll(5, TimeUnit.SECONDS), "the next pass runs, as after an exception");
        }
    }

    @Test
    void aLoopEndedByAnErrorCanStartAgain() throws Exception {
        GameFixture game = new GameFixture();
        BlockingQueue<Integer> passes = new LinkedBlockingQueue<>();
        int[] count = {0};
        try (GameSession session = new GameSession(game.memory, game.map, new EventBus(), 10)) {
            session.setFrameListener(() -> {
                passes.add(++count[0]);
                if (count[0] == 1) {
                    throw new AssertionError("an error no stage catches");
                }
            });
            session.start();
            assertEquals(1, passes.poll(5, TimeUnit.SECONDS));
            Integer next = null;
            for (int tries = 0; tries < 50 && next == null; tries++) {
                session.start();
                next = passes.poll(100, TimeUnit.MILLISECONDS);
            }
            assertEquals(2, next, "the ended loop no longer counts as running, so start runs it again");
        }
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

    @Test
    void aFrameRequestRunsTheFrameListenerWithoutAnEventRead() throws Exception {
        GameFixture game = new GameFixture();
        EventBus bus = new EventBus();
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        bus.register(GameStateChanged.class, seen::add, 0);

        try (GameSession session = new GameSession(game.memory, game.map, bus, NO_TIMER_MS, NO_TIMER_MS)) {
            session.setHookConnected(true);
            session.setFrameListener(() -> frames.add("frame"));
            session.start();
            session.onNative(gameTickRaise(1));
            assertInstanceOf(GameStateChanged.class, seen.poll(5, TimeUnit.SECONDS), "the first read");
            assertEquals("frame", frames.poll(5, TimeUnit.SECONDS), "the raise's pass ends with the frame listener");

            game.setGameState(25);
            session.requestFrame();
            assertEquals("frame", frames.poll(5, TimeUnit.SECONDS), "the request woke the loop at once");
            assertNull(seen.poll(100, TimeUnit.MILLISECONDS),
                    "the request doesn't read the game, so the state change waits for the next raise");
        }
    }

    @Test
    void aClickOnAPluginsRowRunsItsCallbackAndIsPosted() throws Exception {
        GameFixture game = new GameFixture();
        EventBus bus = new EventBus();
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        bus.register(MenuOptionClicked.class, seen::add, 0);

        try (GameSession session = new GameSession(game.memory, game.map, bus, NO_TIMER_MS)) {
            session.start();
            MenuEntry row = session.client().createMenuEntry(-1).setOption("Mark tile")
                    .onClick(e -> seen.add("callback " + e.getOption()));
            session.onMenuClick(new MenuClick(false, row.getRowId(), -1, 3, 4));
            assertEquals("callback Mark tile", seen.poll(5, TimeUnit.SECONDS), "the callback runs first");
            Object posted = seen.poll(5, TimeUnit.SECONDS);
            assertInstanceOf(MenuOptionClicked.class, posted);
            assertEquals(row, ((MenuOptionClicked) posted).getMenuEntry());
            assertEquals("Mark tile", ((MenuOptionClicked) posted).getMenuOption());
        }
    }

    @Test
    void aScriptReportIsPostedAtOnceWithoutAnEventRead() throws Exception {
        GameFixture game = new GameFixture();
        EventBus bus = new EventBus();
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        bus.register(GameStateChanged.class, seen::add, 0);
        bus.register(ScriptPostFired.class, seen::add, 0);

        try (GameSession session = new GameSession(game.memory, game.map, bus, NO_TIMER_MS, NO_TIMER_MS)) {
            session.setHookConnected(true);
            session.start();
            session.onNative(gameTickRaise(1));
            assertInstanceOf(GameStateChanged.class, seen.poll(5, TimeUnit.SECONDS), "the first read");

            game.setGameState(25);
            session.onScript(new ScriptRun(ScriptRun.Kind.POST, 73, 0, ScriptStack.EMPTY, null));
            assertEquals(new ScriptPostFired(73), seen.poll(5, TimeUnit.SECONDS), "posted as the report arrives");
            assertNull(seen.poll(100, TimeUnit.MILLISECONDS),
                    "the report doesn't read the game, so the state change waits for the next raise");
        }
    }
}
