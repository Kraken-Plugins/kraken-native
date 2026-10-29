package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.NPC;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.NpcChanged;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The NPC change events: an {@link NpcChanged} when an NPC is transformed into another type in place, read on the
 * tick's polls and after an NPC update.
 */
class NpcChangeEventsTest {

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<NpcChanged> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(NpcChanged.class, seen::add, 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    @Test
    void aTransformPostsTheOldDefinitionOnTheTick() {
        source.poll();
        NPC goblin = client.npc(5);
        game.setNpcType(5, 3030);
        source.poll();
        assertEquals(List.of(), seen, "read on the tick's polls");

        tick();
        assertEquals(1, seen.size());
        assertSame(goblin, seen.get(0).getNpc(), "the NPC keeps its handle");
        assertEquals(3029, seen.get(0).getOld().getId());
        assertEquals("Goblin", seen.get(0).getOld().getName());
        assertEquals(3030, goblin.getId());
        assertEquals("Goblin chief", goblin.getComposition().getName());

        tick();
        assertEquals(1, seen.size(), "a transform posts once");
    }

    @Test
    void aMultiNpcShowingAnotherChildPostsNothing() {
        source.poll();
        game.setVarp(11, 1);
        tick();

        assertEquals("Goblin chief", client.npc(6).getTransformedComposition().getName());
        assertEquals(List.of(), seen, "its base type stays the same");
    }

    @Test
    void anNpcUpdateReadsWithoutATick() {
        source.poll();
        game.setNpcType(5, 3030);

        source.onNative(new NativeEvent(NativeEvent.NPC_INFO_DECODE, 1000, new long[4], Map.of(), 1));
        source.poll();

        assertEquals(1, seen.size());
    }

    @Test
    void aTypeClearedAsTheNpcIsRemovedPostsNothing() {
        source.poll();
        game.setNpcType(5, -1);
        tick();
        game.setNpcType(5, 3029);
        tick();

        assertEquals(List.of(), seen);
    }

    @Test
    void aNewNpcInTheSameSlotPostsNothing() {
        source.poll();
        game.setNpcSpawnCycle(5, 2000);
        game.setNpcType(5, 3030);
        tick();

        assertEquals(List.of(), seen, "a new NPC spawned there instead");
    }
}
