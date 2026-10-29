package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.VarClientIntChanged;
import com.kraken.api.events.VarClientStrChanged;
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
 * The varc events: a {@link VarClientIntChanged} or {@link VarClientStrChanged} per varc whose value changed, from the
 * varc store compared every poll and from the hook's reports of each set a script makes.
 */
class VarcEventsTest {

    private GameFixture game;
    private EventSource source;
    private final List<Object> seen = new ArrayList<>();
    private long seq;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        Client client = new Client(game.memory, game.map);
        EventBus bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(VarClientIntChanged.class, seen::add, 0);
        bus.register(VarClientStrChanged.class, seen::add, 0);
    }

    private void setInt(int id, int value) {
        source.onNative(new NativeEvent(NativeEvent.SET_VARC_INT, -1,
                new long[]{GameFixture.VARC_STORE, id, value & 0xffffffffL, 0}, Map.of(), ++seq));
    }

    private void setString(int id) {
        source.onNative(new NativeEvent(NativeEvent.SET_VARC_STRING, -1,
                new long[]{GameFixture.VARC_STORE, id, 0x1234, 0}, Map.of(), ++seq));
    }

    private static Object i(int id) {
        return new VarClientIntChanged(id);
    }

    private static Object s(int id) {
        return new VarClientStrChanged(id);
    }

    @Test
    void aChangedVarcPostsAndTheValuesAlreadyThereDoNot() {
        source.poll();
        assertEquals(List.of(), seen, "the varcs were there before tracking started");

        game.setVarcs(Map.of(10, 43, 20, "bye", 30, -5));
        source.poll();
        assertEquals(List.of(i(10), s(20)), seen);

        source.poll();
        assertEquals(2, seen.size(), "a change posts once");
    }

    @Test
    void varcsSetOrDroppedPostInIdOrder() {
        source.poll();
        game.setVarcs(Map.of(10, 42, 40, 1, 50, GameFixture.LONG_TEXT));
        source.poll();
        assertEquals(List.of(i(30), i(40), s(20), s(50)), seen, "ints then strings, each in id order");
    }

    @Test
    void aHeapStringThatChangesPosts() {
        game.setVarcs(Map.of(20, GameFixture.LONG_TEXT));
        source.poll();
        game.setVarcs(Map.of(20, GameFixture.LONG_TEXT + "!"));
        source.poll();
        assertEquals(List.of(s(20)), seen);
    }

    @Test
    void intReportsPostEachChangeInTheOrderTheScriptsMadeThem() {
        source.poll();
        setInt(10, 43);
        setInt(30, -5);
        setInt(10, 44);
        game.setVarcs(Map.of(10, 44, 20, "hello", 30, -5));
        source.poll();
        assertEquals(List.of(i(10), i(10)), seen, "two changes of varc 10; varc 30 was set to the value it held");

        source.poll();
        assertEquals(2, seen.size(), "the read agrees with the reports");
    }

    @Test
    void aReportAheadOfItsWriteSitsOutThatPollsComparison() {
        source.poll();
        setInt(10, 50);
        source.poll();
        assertEquals(List.of(i(10)), seen, "posted from the report though the read still shows 42");

        game.setVarcs(Map.of(10, 50, 20, "hello", 30, -5));
        source.poll();
        assertEquals(1, seen.size(), "the write landing posts nothing more");
    }

    @Test
    void aReportOfAChangeAReadAlreadyShowedPostsNothing() {
        source.poll();
        game.setVarcs(Map.of(10, 43, 20, "hello", 30, -5));
        source.poll();
        setInt(10, 43);
        source.poll();
        assertEquals(List.of(i(10)), seen);
    }

    @Test
    void aStringReportPostsWhenTheReadShowsTheChange() {
        source.poll();
        game.setVarcs(Map.of(10, 42, 20, "bye", 30, -5));
        setString(20);
        setString(20);
        source.poll();
        assertEquals(List.of(s(20)), seen, "two sets, one value read");

        setString(20);
        source.poll();
        assertEquals(1, seen.size(), "a set to the value it holds");
    }

    @Test
    void aStringWriteTheReadMissedPostsOnTheNextPoll() {
        source.poll();
        setString(20);
        source.poll();
        assertEquals(List.of(), seen, "the read still shows the old value");

        game.setVarcs(Map.of(10, 42, 20, "bye", 30, -5));
        source.poll();
        assertEquals(List.of(s(20)), seen);
    }

    @Test
    void anUnreadableStoreKeepsTheBaselineAndTheReports() {
        source.poll();
        long varcs = GameFixture.CLIENT + game.map.offset("Client.varcs");
        game.memory.putLong(varcs, 0xdead0000L);
        setInt(10, 43);
        game.setVarcs(Map.of(10, 43, 20, "hello", 30, 7));
        source.poll();
        assertEquals(List.of(), seen, "nothing while the store can't be read");

        game.memory.putLong(varcs, GameFixture.VARC_STORE);
        source.poll();
        assertEquals(List.of(i(10), i(30)), seen, "the report first, then what the read shows");
    }
}
