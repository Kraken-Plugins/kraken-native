package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.ScriptEvent;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.ScriptPostFired;
import com.kraken.api.events.ScriptPreFired;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.ScriptRequest;
import com.kraken.api.internal.event.ScriptRun;
import com.kraken.api.widgets.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The script events: {@link EventSource#onScript} posts {@link ScriptPreFired} and {@link ScriptPostFired} for each
 * script the native hook reports, with the client's stack getters giving the captured stacks while the handlers run,
 * and each poll tells the hook which of them have subscribers.
 */
class ScriptEventsTest {

    /**
     * The fixture's inventory layer, 149:0, whose dynamic child [0] is an item slot.
     */
    private static final int INVENTORY = 149 << 16;

    private GameFixture game;
    private Client client;
    private EventBus bus;
    private EventSource source;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        bus = new EventBus();
        source = new EventSource(client, bus);
    }

    private <T> void on(Class<T> type, Consumer<T> handler) {
        bus.register(type, handler, 0);
    }

    private static ScriptRun run(ScriptRun.Kind kind, int id, ScriptStack stack, ScriptRequest request) {
        return new ScriptRun(kind, id, kind == ScriptRun.Kind.PRE_GOSUB ? 1 : 0, stack, request);
    }

    @Test
    void aGosubPostsPreFiredWithTheStacksItCaptured() {
        List<Object> seen = new ArrayList<>();
        on(ScriptPreFired.class, event -> {
            seen.add(event);
            seen.add(client.getIntStackSize());
            seen.add(client.getIntStack());
            seen.add(client.getObjectStack());
        });
        ScriptStack stack = new ScriptStack(new int[]{0, 0, 3, 4}, new Object[]{null, "Draynor"});

        source.onScript(run(ScriptRun.Kind.PRE_GOSUB, 1004, stack, null));

        ScriptPreFired fired = (ScriptPreFired) seen.get(0);
        assertEquals(1004, fired.getScriptId());
        assertNull(fired.getScriptEvent(), "a gosub has no event");
        assertEquals(4, seen.get(1));
        assertArrayEquals(new int[]{0, 0, 3, 4}, (int[]) seen.get(2));
        assertArrayEquals(new Object[]{null, "Draynor"}, (Object[]) seen.get(3));
        assertArrayEquals(new int[]{10, 20, 30}, client.getIntStack(), "after the handlers, the game's stack again");
    }

    @Test
    void aRootRunCarriesItsListenersRequest() {
        List<ScriptPreFired> seen = new ArrayList<>();
        on(ScriptPreFired.class, seen::add);
        Object[] arguments = {286, ScriptEvent.WIDGET_ID, ScriptEvent.NAME, 5L};
        ScriptRequest request = new ScriptRequest(INVENTORY, 0, -1, -1, 2, 0, 310, 220, -1, -1, "Wield", arguments);

        source.onScript(run(ScriptRun.Kind.PRE_ROOT, 286, ScriptStack.EMPTY, request));

        ScriptEvent event = seen.get(0).getScriptEvent();
        assertNotNull(event);
        assertArrayEquals(arguments, event.getArguments());
        assertEquals(2, event.getOp());
        assertEquals(0, event.getSubOp());
        assertEquals("Wield", event.getOpbase());
        assertEquals(310, event.getMouseX());
        assertEquals(220, event.getMouseY());
        Widget source = event.getSource();
        assertNotNull(source, "the dynamic child the listener belongs to");
        assertEquals(INVENTORY, source.getId());
        assertEquals(0, source.getIndex());
        assertNull(event.getTarget(), "no target");

        event.getArguments()[0] = 1;
        assertEquals(286, event.getArguments()[0], "the arguments are a copy");
    }

    @Test
    void aRootRunATriggerStartedHasOnlyItsId() {
        List<ScriptPreFired> seen = new ArrayList<>();
        on(ScriptPreFired.class, seen::add);

        source.onScript(run(ScriptRun.Kind.PRE_ROOT, 0x4d0123, ScriptStack.EMPTY, null));

        ScriptEvent event = seen.get(0).getScriptEvent();
        assertArrayEquals(new Object[]{0x4d0123}, event.getArguments());
        assertNull(event.getSource());
        assertEquals(-1, event.getOp());
        assertEquals("", event.getOpbase());
    }

    @Test
    void aReturnPostsPostFiredWithItsReturnValues() {
        List<Object> seen = new ArrayList<>();
        on(ScriptPostFired.class, event -> {
            seen.add(event);
            seen.add(client.getIntStack());
        });
        on(ScriptPreFired.class, seen::add);

        source.onScript(run(ScriptRun.Kind.POST, 2100, new ScriptStack(new int[]{1}, new Object[0]), null));

        assertEquals(List.of(new ScriptPostFired(2100)), seen.subList(0, 1));
        assertArrayEquals(new int[]{1}, (int[]) seen.get(1));
        assertEquals(2, seen.size(), "a return posts no PreFired");
    }

    @Test
    void theHookIsToldWhichEventsHaveSubscribersOnEachPoll() {
        List<String> told = new ArrayList<>();
        source.setScriptHook((pre, post) -> told.add(pre + "," + post));
        assertEquals(List.of("false,false"), told, "a new hook is told at once");

        on(ScriptPreFired.class, event -> { });
        source.poll();
        on(ScriptPostFired.class, event -> { });
        source.poll();
        source.poll();
        assertEquals(List.of("false,false", "true,false", "true,true"), told, "only changes are sent");

        source.setScriptHook(null);
        source.poll();
        assertEquals(3, told.size(), "nothing is sent without a hook");
    }
}
