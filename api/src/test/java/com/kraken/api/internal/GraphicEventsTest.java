package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.Actor;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.GraphicChanged;
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

/**
 * The graphic events: a {@link GraphicChanged} when an actor's spot animations change (one set, set again, cleared or
 * finished), read on the tick's polls and after an NPC or player update, and for the actors showing one on every poll.
 */
class GraphicEventsTest {

    private static final int TELEPORT = 111;
    private static final int SPLASH = 85;

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<Actor> seen = new ArrayList<>();
    private final List<Integer> graphics = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(GraphicChanged.class, e -> {
            seen.add(e.getActor());
            graphics.add(e.getActor().getSpotAnims().isEmpty() ? -1 : e.getActor().getSpotAnims().get(0).getId());
        }, 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    private void raise(String name) {
        source.onNative(new NativeEvent(name, 1000, new long[4], Map.of(), 1));
    }

    private static int[] spotAnim(int slot, int id, int startCycle) {
        return new int[]{slot, id, 92, startCycle, 0};
    }

    @Test
    void actorsAlreadyShowingASpotAnimPostAsTrackingStarts() {
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        source.poll();

        assertEquals(List.of(client.getLocalPlayer()), seen, "only the local player shows one");
        assertEquals(List.of(TELEPORT), graphics);
    }

    @Test
    void aSpotAnimBeingSetPostsOnTheTick() {
        source.poll();
        game.setSpotAnims(GameFixture.NPC, spotAnim(0, SPLASH, CYCLE + 2));
        source.poll();
        assertEquals(List.of(), seen, "sets are read on the tick's polls");

        tick();
        assertEquals(List.of(client.npc(5)), seen);
        assertEquals(List.of(SPLASH), graphics);
    }

    @Test
    void aSpotAnimSetAgainInItsSlotPostsAgain() {
        source.poll();
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        tick();
        tick();
        assertEquals(1, seen.size(), "the same set read again posts nothing");

        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE + 30));
        tick();
        assertEquals(List.of(TELEPORT, TELEPORT), graphics, "a new set starts on another cycle");
    }

    @Test
    void anotherSlotBeingSetPostsAgain() {
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        source.poll();
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE), spotAnim(1, SPLASH, CYCLE));
        source.poll();

        assertEquals(2, seen.size(), "an actor showing one is read every poll");
    }

    @Test
    void aSpotAnimEndingPostsWithoutATick() {
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        source.poll();
        game.setSpotAnims(GameFixture.PLAYER);
        source.poll();

        assertEquals(List.of(client.getLocalPlayer(), client.getLocalPlayer()), seen);
        assertEquals(List.of(TELEPORT, -1), graphics);
    }

    @Test
    void aPlayerUpdateReadsWithoutATick() {
        source.poll();
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));

        raise(NativeEvent.PLAYER_INFO_DECODE);
        source.poll();

        assertEquals(List.of(TELEPORT), graphics);
    }

    @Test
    void slotsThatCantBeReadKeepTheLastRead() {
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        source.poll();
        game.breakSpotAnims(GameFixture.PLAYER);
        source.poll();
        tick();
        game.setSpotAnims(GameFixture.PLAYER, spotAnim(0, TELEPORT, CYCLE));
        tick();

        assertEquals(1, seen.size(), "a broken read didn't look like the spot animation ending and starting again");
    }

    @Test
    void anActorComingIntoViewShowingOnePostsOne() {
        game.setNpcLoaded(5, false);
        source.poll();
        game.setSpotAnims(GameFixture.NPC, spotAnim(0, SPLASH, CYCLE));
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(List.of(client.npc(5)), seen);
    }
}
