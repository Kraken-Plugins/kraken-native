package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.WidgetClosed;
import com.kraken.api.events.WidgetLoaded;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.widgets.WidgetModalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.CLIENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interface events over the {@link GameFixture}: loads and closes from comparing the component table and the
 * top-level interface, and from the open and close functions' hook reports.
 */
class WidgetEventsTest {

    private static final int TOPLEVEL = 161;
    private static final int INVENTORY = 149;
    private static final int CHATBOX = 162;
    private static final int BANK = 150;
    private static final int SIDE_PANEL = TOPLEVEL << 16 | 1;
    private static final int OTHER_PANEL = TOPLEVEL << 16 | 2;
    private static final int MAIN_MODAL = TOPLEVEL << 16 | 5;
    private static final int[] INVENTORY_OPEN = {SIDE_PANEL, INVENTORY, WidgetModalMode.NON_MODAL};

    private GameFixture game;
    private EventSource source;
    private EventBus bus;
    private final List<Object> seen = new ArrayList<>();
    private long seq;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        bus = new EventBus();
        source = new EventSource(new Client(game.memory, game.map), bus);
    }

    private void record() {
        bus.register(WidgetLoaded.class, seen::add, 0);
        bus.register(WidgetClosed.class, seen::add, 0);
    }

    /**
     * Subscribes after the first poll, so tracking starts with a silent baseline rather than a replay.
     */
    private void recordFromNow() {
        source.poll();
        record();
        source.poll();
    }

    private static WidgetLoaded loaded(int group) {
        WidgetLoaded event = new WidgetLoaded();
        event.setGroupId(group);
        return event;
    }

    private void opened(int component, int group, int mode) {
        source.onNative(new NativeEvent(NativeEvent.IF_OPEN_SUB, 1000, new long[]{CLIENT, component, group, mode},
                Map.of(), ++seq));
    }

    private void closed(int component, int group, boolean unload) {
        source.onNative(new NativeEvent(NativeEvent.IF_CLOSE_SUB, 1001,
                new long[]{CLIENT, component, group, unload ? 0xabcd01 : 0xabcd00}, Map.of(), ++seq));
    }

    @Test
    void attachingReplaysTheTopLevelInterfaceThenEveryOpenInterfaceAsLoaded() {
        record();
        source.poll();
        assertEquals(List.of(loaded(TOPLEVEL), loaded(INVENTORY)), seen);
    }

    @Test
    void aGroupOpenedInAComponentPostsALoadAndClosingItPostsAClose() {
        recordFromNow();
        assertTrue(seen.isEmpty(), "a silent baseline");

        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        source.poll();
        source.poll();
        assertEquals(List.of(loaded(BANK)), seen);

        game.setComponentTable(INVENTORY_OPEN);
        source.poll();
        assertEquals(List.of(loaded(BANK), new WidgetClosed(BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH, true)), seen);
    }

    @Test
    void aGroupMovedToAnotherComponentPostsNothing() {
        recordFromNow();
        game.setComponentTable(new int[]{OTHER_PANEL, INVENTORY, WidgetModalMode.NON_MODAL});
        source.poll();
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aGroupReplacedInItsComponentPostsTheCloseThenTheLoad() {
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, BANK, WidgetModalMode.MODAL_CLICKTHROUGH});
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, CHATBOX, WidgetModalMode.MODAL_CLICKTHROUGH});
        source.poll();
        assertEquals(List.of(new WidgetClosed(BANK, WidgetModalMode.MODAL_CLICKTHROUGH, true), loaded(CHATBOX)), seen);
    }

    @Test
    void aNewTopLevelInterfacePostsALoadAndTheOneItReplacesNothing() {
        recordFromNow();
        game.loadGroup(164);
        game.setTopLevelInterface(164);
        source.poll();
        game.setTopLevelInterface(-1);
        source.poll();
        assertEquals(List.of(loaded(164)), seen);
    }

    @Test
    void aLoadWaitsUntilTheClientHoldsTheGroupsComponentsAndTheEventsBehindItWaitToo() {
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, 170, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        source.poll();
        game.setComponentTable(new int[]{MAIN_MODAL, 170, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        source.poll();
        assertTrue(seen.isEmpty(), "group 170 isn't loaded yet: " + seen);

        game.loadGroup(170);
        source.poll();
        assertEquals(List.of(loaded(170), new WidgetClosed(INVENTORY, WidgetModalMode.NON_MODAL, true)), seen);
    }

    @Test
    void aLoadStopsWaitingForAGroupTheClientNeverLoads() {
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, 171, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        int polls = 0;
        while (seen.isEmpty() && polls++ < 100) {
            source.poll();
        }
        assertEquals(List.of(loaded(171)), seen);
        assertTrue(polls > 10, "it waited " + polls + " polls");
    }

    @Test
    void aGroupClosedWhileItsLoadWaitsPostsBoth() {
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, 172, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        source.poll();
        game.setComponentTable(INVENTORY_OPEN);
        source.poll();
        assertEquals(List.of(loaded(172), new WidgetClosed(172, WidgetModalMode.MODAL_NOCLICKTHROUGH, true)), seen);
    }

    @Test
    void aGroupReopenedInTheSameComponentPostsACloseWithoutUnloadThenALoad() {
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, CHATBOX, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        recordFromNow();

        closed(MAIN_MODAL, CHATBOX, false);
        opened(MAIN_MODAL, CHATBOX, WidgetModalMode.MODAL_NOCLICKTHROUGH);
        source.poll();
        source.poll();
        assertEquals(List.of(new WidgetClosed(CHATBOX, WidgetModalMode.MODAL_NOCLICKTHROUGH, false), loaded(CHATBOX)),
                seen);
    }

    @Test
    void aGroupOpenedAndClosedBetweenTwoPollsPostsBoth() {
        recordFromNow();
        opened(MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH);
        closed(MAIN_MODAL, BANK, true);
        source.poll();
        assertEquals(List.of(loaded(BANK), new WidgetClosed(BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH, true)), seen);
    }

    @Test
    void anOpenReportedAfterAReadSawItPostsOnce() {
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        source.poll();
        opened(MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH);
        source.poll();
        assertEquals(List.of(loaded(BANK)), seen);
    }

    @Test
    void aCloseReportedBeforeTheTableChangesPostsOnce() {
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        recordFromNow();

        closed(MAIN_MODAL, BANK, true);
        source.poll();
        game.setComponentTable(INVENTORY_OPEN);
        source.poll();
        assertEquals(List.of(new WidgetClosed(BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH, true)), seen);
    }

    @Test
    void anOpenReportedOverAnUnreportedCloseClosesTheGroupItReplaces() {
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH});
        recordFromNow();

        opened(MAIN_MODAL, CHATBOX, WidgetModalMode.MODAL_CLICKTHROUGH);
        source.poll();
        assertEquals(List.of(new WidgetClosed(BANK, WidgetModalMode.MODAL_NOCLICKTHROUGH, true), loaded(CHATBOX)),
                seen);
    }

    @Test
    void aReadOfAnEntryNoInterfaceCouldHaveKeepsTheLastOne() {
        recordFromNow();
        game.setComponentTable(INVENTORY_OPEN, new int[]{MAIN_MODAL, 0x12345, 0});
        source.poll();
        game.setComponentTable(INVENTORY_OPEN);
        source.poll();
        assertTrue(seen.isEmpty(), "a torn read posts nothing: " + seen);
    }
}
