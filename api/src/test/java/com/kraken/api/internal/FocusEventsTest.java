package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.FocusChanged;
import com.kraken.api.internal.event.EventSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The focus events: a {@link FocusChanged} each time {@code Client.isFocused()} changes between polls.
 */
class FocusEventsTest {

    private Client client;
    private EventSource source;
    private final List<FocusChanged> seen = new ArrayList<>();
    private boolean focused = true;

    @BeforeEach
    void setUp() throws IOException {
        GameFixture game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.setFocusSource(() -> focused);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(FocusChanged.class, seen::add, 0);
    }

    @Test
    void eachChangePostsTheNewState() {
        source.poll();
        assertEquals(List.of(), seen, "the state when tracking starts posts nothing");

        focused = false;
        source.poll();
        source.poll();
        focused = true;
        source.poll();

        assertEquals(List.of(new FocusChanged(false), new FocusChanged(true)), seen);
    }

    @Test
    void aClientWithoutAFocusCheckIsNeverFocused() throws IOException {
        GameFixture game = new GameFixture();
        assertFalse(new Client(game.memory, game.map).isFocused());
    }
}
