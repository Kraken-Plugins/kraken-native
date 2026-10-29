package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.OverheadTextChanged;
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
 * The overhead text events: an {@link OverheadTextChanged} when an actor's overhead text is set, read on the tick's
 * polls and after an NPC or player update, telling the same line said again from its timer and the client's count of
 * actor steps.
 */
class OverheadTextEventsTest {

    private static final String SHOUT = "You shall not pass, little adventurer!";

    private GameFixture game;
    private Client client;
    private EventSource source;
    private final List<OverheadTextChanged> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        game.setActorSteps(1000);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(OverheadTextChanged.class, seen::add, 0);
    }

    private void tick() {
        game.setTick(client.tickCount() + 1);
        source.poll();
    }

    private void raise(String name) {
        source.onNative(new NativeEvent(name, 1000, new long[4], Map.of(), 1));
    }

    private List<String> texts() {
        return seen.stream().map(OverheadTextChanged::getOverheadText).toList();
    }

    @Test
    void aLinePostsOnTheTickWithItsActorAndText() {
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        source.poll();
        assertEquals(List.of(), seen, "lines already showing when tracking starts post nothing");

        game.setOverheadText(GameFixture.NPC, SHOUT, 100);
        source.poll();
        assertEquals(List.of(), seen, "lines are read on the tick's polls");

        tick();
        assertEquals(List.of(SHOUT), texts());
        assertSame(client.npc(5), seen.get(0).getActor());
    }

    @Test
    void anNpcUpdateReadsWithoutATick() {
        source.poll();
        game.setOverheadText(GameFixture.NPC, SHOUT, 100);

        raise(NativeEvent.NPC_INFO_DECODE);
        source.poll();

        assertEquals(List.of(SHOUT), texts());
    }

    @Test
    void aLineCountingDownAndTimingOutPostsNothingMore() {
        source.poll();
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();
        game.stepActors(30);
        tick();
        game.stepActors(120);
        tick();

        assertEquals(List.of("Buying gf"), texts());
    }

    @Test
    void theSameLineSaidAgainPostsAgain() {
        source.poll();
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();
        game.stepActors(30);
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();

        assertEquals(List.of("Buying gf", "Buying gf"), texts(), "its timer is past what the countdown allows");
    }

    @Test
    void anotherLinePostsWhateverItsTimer() {
        source.poll();
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();
        game.stepActors(30);
        game.setOverheadText(GameFixture.PLAYER, "Selling gf", 100);
        tick();

        assertEquals(List.of("Buying gf", "Selling gf"), texts());
    }

    @Test
    void aStepCounterThatWentBackOnlyTrustsARisingTimer() {
        source.poll();
        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();
        game.stepActors(30);
        game.setActorSteps(0);
        tick();
        assertEquals(1, seen.size(), "a timer that went down isn't a new line");

        game.setOverheadText(GameFixture.PLAYER, "Buying gf", 150);
        tick();
        assertEquals(2, seen.size(), "a timer that went up is");
    }

    @Test
    void textThatCantBeReadKeepsTheLastRead() {
        source.poll();
        game.setOverheadText(GameFixture.NPC, SHOUT, 100);
        tick();
        long string = GameFixture.NPC + game.map.offset("Actor.overheadText");
        long heap = game.memory.readLong(string);
        game.memory.putLong(string, 0xdead0000L);
        tick();
        game.memory.putLong(string, heap);
        tick();

        assertEquals(List.of(SHOUT), texts(), "a failed read didn't look like the line being said again");
    }

    @Test
    void anActorComingIntoViewSayingSomethingPostsIt() {
        game.setNpcLoaded(5, false);
        source.poll();
        game.setOverheadText(GameFixture.NPC, SHOUT, 100);
        game.setNpcLoaded(5, true);
        tick();

        assertEquals(List.of(SHOUT), texts());
    }
}
