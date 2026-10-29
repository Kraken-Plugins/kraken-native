package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.Actor;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.ActorDeath;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death events: an {@link ActorDeath} when an actor's health bar starts showing empty, read on the tick's polls and
 * after an NPC or player update.
 */
class ActorDeathEventsTest {

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<Actor> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(ActorDeath.class, e -> seen.add(e.getActor()), 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    @Test
    void actorsAlreadyDeadWhenTrackingStartsPostNothing() {
        source.poll();
        tick();

        assertEquals(List.of(), seen, "the goblin's health bar was empty before tracking started");
    }

    @Test
    void anActorDyingPostsOnceOnTheTick() {
        game.setHealthbar(GameFixture.PLAYER, 10);
        source.poll();
        game.setHealthbar(GameFixture.PLAYER, 0);
        source.poll();
        assertEquals(List.of(), seen, "read on the tick's polls");

        tick();
        assertEquals(List.of(client.getLocalPlayer()), seen);
        assertTrue(client.getLocalPlayer().isDead());

        tick();
        assertEquals(1, seen.size(), "a death posts once");
    }

    @Test
    void anActorThatCameBackToLifeCanDieAgain() {
        source.poll();
        game.setHealthbar(GameFixture.NPC, 4);
        tick();
        assertEquals(List.of(), seen);

        game.setHealthbar(GameFixture.NPC, 0);
        tick();
        assertEquals(List.of(client.npc(5)), seen);
    }

    @Test
    void headbarsThatCantBeReadDontLookLikeASecondDeath() {
        game.setHealthbar(GameFixture.PLAYER, 10);
        source.poll();
        game.setHealthbar(GameFixture.PLAYER, 0);
        tick();
        game.breakHeadbars(GameFixture.PLAYER);
        tick();
        game.setHealthbar(GameFixture.PLAYER, 0);
        tick();

        assertEquals(List.of(client.getLocalPlayer()), seen, "the broken read kept the death");
    }

    @Test
    void anNpcUpdateReadsWithoutATick() {
        game.setHealthbar(GameFixture.NPC, 3);
        source.poll();
        game.setHealthbar(GameFixture.NPC, 0);

        source.onNative(new NativeEvent(NativeEvent.NPC_INFO_DECODE, 1000, new long[4], Map.of(), 1));
        source.poll();

        assertEquals(List.of(client.npc(5)), seen);
    }

    @Test
    void anActorComingIntoViewDeadPostsOne() {
        game.setNpcLoaded(5, false);
        source.poll();
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(List.of(client.npc(5)), seen);
    }
}
