package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Hitsplat;
import com.kraken.api.HitsplatID;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.HitsplatApplied;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.CYCLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hitsplat events: a {@link HitsplatApplied} for each slot of an actor's hitsplats that holds a new one, read on
 * the tick's polls and after an NPC or player update.
 */
class HitsplatEventsTest {

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<HitsplatApplied> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(HitsplatApplied.class, seen::add, 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    private static int[] splat(int type, int amount, int ends) {
        return new int[]{type, amount, ends};
    }

    @Test
    void aNewHitsplatPostsOnTheTickWithItsActor() {
        game.setHitsplats(GameFixture.NPC, splat(HitsplatID.DAMAGE_OTHER, 3, CYCLE + 20));
        source.poll();
        assertEquals(List.of(), seen, "hitsplats already showing when tracking starts post nothing");

        game.setHitsplats(GameFixture.NPC, splat(HitsplatID.DAMAGE_OTHER, 3, CYCLE + 20),
                splat(HitsplatID.DAMAGE_ME, 12, CYCLE + 60));
        source.poll();
        assertEquals(List.of(), seen, "read on the tick's polls");

        tick();
        assertEquals(1, seen.size());
        assertSame(client.npc(5), seen.get(0).getActor());
        assertEquals(new Hitsplat(HitsplatID.DAMAGE_ME, 12, CYCLE + 60), seen.get(0).getHitsplat());
        assertTrue(seen.get(0).getHitsplat().isMine());

        tick();
        assertEquals(1, seen.size(), "an unchanged slot posts once");
    }

    @Test
    void aSlotWrittenOverPostsTheNewHitsplat() {
        game.setHitsplats(GameFixture.PLAYER, splat(HitsplatID.BLOCK_ME, 0, CYCLE - 10));
        source.poll();

        game.setHitsplats(GameFixture.PLAYER, splat(HitsplatID.BLOCK_ME, 0, CYCLE + 70));
        tick();

        assertEquals(1, seen.size());
        assertSame(client.getLocalPlayer(), seen.get(0).getActor());
        assertEquals(CYCLE + 70, seen.get(0).getHitsplat().getDisappearsOnGameCycle());
    }

    @Test
    void anUpdateTriggerReadsWithoutATick() {
        source.poll();
        game.setHitsplats(GameFixture.PLAYER, splat(HitsplatID.HEAL, 5, CYCLE + 40));

        source.onNative(new NativeEvent(NativeEvent.PLAYER_INFO_DECODE, 1000, new long[4], Map.of(), 1));
        source.poll();

        assertEquals(1, seen.size());
        Hitsplat heal = seen.get(0).getHitsplat();
        assertEquals(5, heal.getAmount());
        assertFalse(heal.isMine());
        assertFalse(heal.isOthers());
    }

    @Test
    void anActorSeenForTheFirstTimePostsTheHitsplatsItStillShows() {
        game.setNpcLoaded(5, false);
        source.poll();

        game.setHitsplats(GameFixture.NPC, splat(HitsplatID.DAMAGE_OTHER, 1, CYCLE - 5),
                splat(HitsplatID.DAMAGE_OTHER, 2, CYCLE + 5));
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(1, seen.size(), "the ended one isn't posted");
        assertEquals(2, seen.get(0).getHitsplat().getAmount());
        assertTrue(seen.get(0).getHitsplat().isOthers());
    }
}
