package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.events.MenuOpened;
import com.kraken.api.events.MenuOptionClicked;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.MENU_ATTACK;
import static com.kraken.api.GameFixture.MENU_CANCEL;
import static com.kraken.api.GameFixture.MENU_CHOP;
import static com.kraken.api.GameFixture.MENU_ROWS;
import static com.kraken.api.GameFixture.MENU_TAKE;
import static com.kraken.api.GameFixture.MENU_WALK;
import static com.kraken.api.GameFixture.TREE_LOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu events over the {@link GameFixture}: entries added and removed as the rows change, the menu opening, and a
 * click for each entry the client runs, whether a poll or an op event notices it.
 */
class MenuEventsTest {

    private GameFixture game;
    private EventSource source;
    private EventBus bus;
    private final List<Object> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        bus = new EventBus();
        source = new EventSource(new Client(game.memory, game.map), bus);
    }

    private void record(Class<?>... types) {
        for (Class<?> type : types) {
            bus.register(type, seen::add, 0);
        }
    }

    /**
     * Subscribes after the first poll, so tracking starts with a silent baseline rather than a replay.
     *
     * @param types the events to record
     */
    private void recordFromNow(Class<?>... types) {
        source.poll();
        record(types);
        source.poll();
    }

    private <T> List<T> seen(Class<T> type) {
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void attachingReplaysTheMenuAsAddedTopFirst() {
        record(MenuEntryAdded.class);
        source.poll();
        List<MenuEntryAdded> added = seen(MenuEntryAdded.class);
        assertEquals(MENU_ROWS, added.size());
        assertEquals("Attack", added.get(0).getOption());
        assertEquals(MenuAction.NPC_SECOND_OPTION.getId(), added.get(0).getType());
        assertEquals("Cancel", added.get(MENU_ROWS - 1).getOption());
    }

    @Test
    void rowsThatChangePostRemovedThenAdded() {
        recordFromNow(MenuEntryAdded.class, MenuEntryRemoved.class);
        source.poll();
        assertTrue(seen.isEmpty(), "the client rebuilding the same menu posts nothing");

        game.setMenuRows(MENU_WALK, MENU_CANCEL);
        source.poll();
        assertEquals(MENU_ROWS - 2, seen(MenuEntryRemoved.class).size());
        assertEquals("Attack", seen(MenuEntryRemoved.class).get(0).getOption());
        assertTrue(seen(MenuEntryAdded.class).isEmpty());

        seen.clear();
        game.setMenuRows(MENU_TAKE, MENU_WALK, MENU_CANCEL);
        source.poll();
        assertEquals(1, seen.size());
        MenuEntryAdded take = assertInstanceOf(MenuEntryAdded.class, seen.get(0));
        assertEquals("Take", take.getOption());
        assertEquals(995, take.getIdentifier());
        assertEquals(3, take.getActionParam0());

        seen.clear();
        game.setMenuRows(MENU_CHOP, MENU_WALK, MENU_CANCEL);
        source.poll();
        assertEquals(2, seen.size());
        assertInstanceOf(MenuEntryRemoved.class, seen.get(0), "removals come before additions");
        assertEquals("Chop down", assertInstanceOf(MenuEntryAdded.class, seen.get(1)).getOption());
    }

    @Test
    void openingTheMenuPostsItsEntries() {
        recordFromNow(MenuOpened.class);
        game.setMenuOpen(true);
        source.poll();
        source.poll();
        List<MenuOpened> opened = seen(MenuOpened.class);
        assertEquals(1, opened.size(), "once per opening");
        assertEquals(MENU_ROWS, opened.get(0).getMenuEntries().length);
        assertEquals("Attack", opened.get(0).getFirstEntry().getOption());
        assertEquals("Cancel", opened.get(0).getMenuEntries()[0].getOption());

        game.setMenuOpen(false);
        source.poll();
        game.setMenuOpen(true);
        source.poll();
        assertEquals(2, seen(MenuOpened.class).size());
    }

    @Test
    void eachEntryTheClientRunsPostsAClick() {
        recordFromNow(MenuOptionClicked.class);
        game.setMenuClickCycles(101);
        source.poll();
        assertTrue(seen.isEmpty(), "the last run isn't replayed, and a growing count is no run");

        game.clickMenuEntry(MENU_CHOP);
        source.poll();
        List<MenuOptionClicked> clicks = seen(MenuOptionClicked.class);
        assertEquals(1, clicks.size());
        assertEquals(MenuAction.GAME_OBJECT_FIRST_OPTION, clicks.get(0).getMenuAction());
        assertEquals("Chop down", clicks.get(0).getMenuOption());
        assertEquals(TREE_LOC, clicks.get(0).getId());
        assertEquals(5, clicks.get(0).getParam0());

        game.setMenuClickCycles(2);
        source.poll();
        game.clickMenuEntry(MENU_CHOP);
        source.poll();
        assertEquals(2, seen(MenuOptionClicked.class).size(), "the same entry again: the count started over");

        game.clickMenuEntry(MENU_CANCEL);
        source.poll();
        assertEquals(3, seen(MenuOptionClicked.class).size(), "another entry in the same cycle: the copy changed");
        assertEquals(MenuAction.CANCEL, seen(MenuOptionClicked.class).get(2).getMenuAction());
    }

    @Test
    void anOpEventForAPostedClickPostsNothingMore() {
        recordFromNow(MenuOptionClicked.class);
        game.clickMenuEntry(MENU_ATTACK);
        source.onNative(new NativeEvent("ON_NPC_OP", 23, new long[4], Map.of(), 1));
        source.poll();
        assertEquals(1, seen.size());
        assertEquals("Attack", seen(MenuOptionClicked.class).get(0).getMenuOption());

        source.onNative(new NativeEvent("ON_NPC_OP", 23, new long[4], Map.of(), 2));
        source.poll();
        assertEquals(1, seen.size());
    }

    @Test
    void aMovedMapBaseIsNoClick() {
        recordFromNow(MenuOptionClicked.class);
        game.clickMenuEntry(MENU_CHOP);
        source.poll();
        game.setMenuClickCycles(5);
        game.setMapBase(BASE_X - 8, BASE_Z);
        source.poll();
        assertEquals(1, seen.size());
    }

    @Test
    void noMenuEventsWithoutSubscribers() {
        source.poll();
        game.clickMenuEntry(MENU_CHOP);
        game.setMenuRows(MENU_CANCEL);
        source.poll();
        record(MenuOptionClicked.class, MenuEntryRemoved.class);
        source.poll();
        assertTrue(seen.isEmpty(), "tracking that starts late takes a silent baseline");
    }
}
