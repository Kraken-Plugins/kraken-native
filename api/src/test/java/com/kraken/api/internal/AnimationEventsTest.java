package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.Actor;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.AnimationChanged;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The animation events: an {@link AnimationChanged} when an actor's animation starts, changes or ends, read on the
 * tick's polls and after an update or an NPC's animation being set, and for the actors playing one on every poll.
 */
class AnimationEventsTest {

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<Actor> seen = new ArrayList<>();
    private final List<Integer> animations = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(AnimationChanged.class, e -> {
            seen.add(e.getActor());
            animations.add(e.getActor().getAnimation());
        }, 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    private void raise(String name) {
        source.onNative(new NativeEvent(name, 1000, new long[4], Map.of(), 1));
    }

    @Test
    void actorsAlreadyPlayingAnAnimationPostAsTrackingStarts() {
        game.setAnimation(GameFixture.NPC, 422);
        source.poll();

        assertEquals(List.of(client.npc(5)), seen, "only the goblin plays an animation");
        assertEquals(List.of(422), animations);
    }

    @Test
    void anAnimationStartingPostsOnTheTick() {
        source.poll();
        game.setAnimation(GameFixture.PLAYER, 879);
        source.poll();
        assertEquals(List.of(), seen, "starts are read on the tick's polls");

        tick();
        assertEquals(List.of(client.getLocalPlayer()), seen);
        assertEquals(List.of(879), animations);

        game.setAnimation(GameFixture.PLAYER, 879);
        tick();
        assertEquals(1, seen.size(), "the same animation set again posts nothing");
    }

    @Test
    void anAnimationEndingPostsWithoutATick() {
        game.setAnimation(GameFixture.PLAYER, 879);
        source.poll();
        game.setAnimation(GameFixture.PLAYER, -1);
        source.poll();

        assertEquals(List.of(client.getLocalPlayer(), client.getLocalPlayer()), seen,
                "the actors playing an animation are read every poll");
        assertEquals(List.of(879, -1), animations);
    }

    @Test
    void anotherAnimationReplacingOnePostsWithoutATick() {
        game.setAnimation(GameFixture.NPC, 422);
        source.poll();
        game.setAnimation(GameFixture.NPC, 423);
        source.poll();

        assertEquals(List.of(422, 423), animations);
    }

    @Test
    void anNpcAnimationBeingSetReadsWithoutATick() {
        source.poll();
        game.setAnimation(GameFixture.NPC, 422);

        raise(NativeEvent.SET_SEQUENCE);
        source.poll();

        assertEquals(List.of(client.npc(5)), seen);
    }

    @Test
    void aPlayerUpdateReadsWithoutATick() {
        source.poll();
        game.setAnimation(GameFixture.PLAYER, 866);

        raise(NativeEvent.PLAYER_INFO_DECODE);
        source.poll();

        assertEquals(List.of(866), animations);
    }

    @Test
    void anActorLeavingWhileAnimatingPostsNothing() {
        game.setAnimation(GameFixture.NPC, 422);
        source.poll();
        game.setNpcLoaded(5, false);
        source.poll();
        tick();

        assertEquals(1, seen.size(), "only the one as tracking started");
    }

    @Test
    void anActorComingIntoViewAnimatingPostsOne() {
        game.setNpcLoaded(5, false);
        source.poll();
        game.setAnimation(GameFixture.NPC, 422);
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(List.of(client.npc(5)), seen);
    }

    @Test
    void anActorTheClientListsButCantBePinnedKeepsItsAnimation() {
        game.setAnimation(GameFixture.NPC, 422);
        source.poll();
        game.memory.putLong(GameFixture.BUCKETS_NPC, 0);
        tick();
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(1, seen.size(), "the goblin didn't leave and come back");
    }
}
