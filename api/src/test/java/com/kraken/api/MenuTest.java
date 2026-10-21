package com.kraken.api;

import com.kraken.api.coords.WorldPoint;
import com.kraken.api.widgets.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.MENU_ATTACK;
import static com.kraken.api.GameFixture.MENU_CANCEL;
import static com.kraken.api.GameFixture.MENU_CHOP;
import static com.kraken.api.GameFixture.MENU_DROP;
import static com.kraken.api.GameFixture.MENU_EXAMINE;
import static com.kraken.api.GameFixture.MENU_ROWS;
import static com.kraken.api.GameFixture.MENU_TAKE;
import static com.kraken.api.GameFixture.MENU_TRADE;
import static com.kraken.api.GameFixture.MENU_USE_ON_GOBLIN;
import static com.kraken.api.GameFixture.MENU_WALK;
import static com.kraken.api.GameFixture.TREE_LOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimenu over the {@link GameFixture}: the rows' order, the action, identifier and parameters worked out of
 * each native entry, the lookups an entry makes, and the open flag.
 */
class MenuTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    /**
     * The entry on a row, counting rows from the top.
     *
     * @param row the row
     * @return its entry
     */
    private MenuEntry row(int row) {
        MenuEntry[] entries = client.getMenuEntries();
        return entries[entries.length - 1 - row];
    }

    @Test
    void readsTheRowsBottomFirstWithTheLeftClickEntryLast() {
        MenuEntry[] entries = client.getMenuEntries();
        assertEquals(MENU_ROWS, entries.length);
        assertEquals("Cancel", entries[0].getOption());
        assertEquals(MenuAction.CANCEL, entries[0].getType());
        MenuEntry top = entries[MENU_ROWS - 1];
        assertEquals("Attack", top.getOption());
        assertEquals("<col=ffff00>Goblin<col=ff00>  (level-2)", top.getTarget(), "a target held in the entry's buffer");
        assertEquals(MenuAction.NPC_SECOND_OPTION, top.getType());
        assertEquals(5, top.getIdentifier());
        assertEquals(0, top.getParam0());
        assertNull(top.getWorldLocation());
    }

    @Test
    void npcAndPlayerEntriesLookTheirActorUp() {
        MenuEntry attack = row(MENU_ATTACK);
        assertSame(client.npc(5), attack.getNpc());
        assertSame(attack.getNpc(), attack.getActor());
        assertNull(attack.getPlayer());
        assertNull(attack.getWidget());

        MenuEntry trade = row(MENU_TRADE);
        assertEquals(MenuAction.PLAYER_FOURTH_OPTION, trade.getType());
        assertEquals(9, trade.getIdentifier());
        assertSame(client.player(9), trade.getPlayer());
        assertNull(trade.getNpc());

        MenuEntry examine = row(MENU_EXAMINE);
        assertEquals(MenuAction.EXAMINE_NPC, examine.getType());
        assertEquals(5, examine.getIdentifier());
    }

    @Test
    void locGroundItemAndWalkEntriesCarryTheSceneTile() {
        MenuEntry chop = row(MENU_CHOP);
        assertEquals(MenuAction.GAME_OBJECT_FIRST_OPTION, chop.getType());
        assertEquals(TREE_LOC, chop.getIdentifier());
        assertEquals(5, chop.getParam0());
        assertEquals(5, chop.getParam1());
        assertEquals(new WorldPoint(BASE_X + 5, BASE_Z + 5, 0), chop.getWorldLocation());

        MenuEntry take = row(MENU_TAKE);
        assertEquals(MenuAction.GROUND_ITEM_THIRD_OPTION, take.getType());
        assertEquals(995, take.getIdentifier());
        assertEquals(3, take.getParam0());
        assertEquals(3, take.getParam1());
        assertEquals(-1, take.getItemId(), "an item id comes from a component; a ground item's is the identifier");

        MenuEntry walk = row(MENU_WALK);
        assertEquals(MenuAction.WALK, walk.getType());
        assertEquals("", walk.getTarget());
        assertEquals(2, walk.getParam0());
        assertEquals(3, walk.getParam1());
    }

    @Test
    void componentOpsNameTheirWidgetAndItsItem() {
        MenuEntry drop = row(MENU_DROP);
        assertEquals(MenuAction.CC_OP, drop.getType());
        assertEquals(3, drop.getIdentifier(), "the third op: the native op index counts from 0");
        assertEquals(0, drop.getParam0());
        assertEquals(149 << 16, drop.getParam1());
        Widget slot = drop.getWidget();
        assertNotNull(slot);
        assertEquals(0, slot.getIndex());
        assertEquals(995, drop.getItemId());
    }

    @Test
    void aSubmenuRowReadsAsItsFirstEntry() {
        MenuEntry use = row(MENU_USE_ON_GOBLIN);
        assertEquals("Use", use.getOption());
        assertEquals("<col=ff9040>Coins<col=ffffff> -> <col=ffff00>Goblin", use.getTarget());
        assertEquals(MenuAction.WIDGET_TARGET_ON_NPC, use.getType());
        assertSame(client.npc(5), use.getNpc());
    }

    @Test
    void readsTheOpenFlag() {
        assertFalse(client.isMenuOpen());
        game.setMenuOpen(true);
        assertTrue(client.isMenuOpen());
    }

    @Test
    void entriesCompareByWhatTheClientHolds() {
        MenuEntry before = row(MENU_CHOP);
        game.setMapBase(BASE_X - 8, BASE_Z);
        MenuEntry after = row(MENU_CHOP);
        assertEquals(13, after.getParam0(), "the scene parameters follow the map base");
        assertEquals(before, after, "but the entry is the one the client held before");
        assertEquals(before.hashCode(), after.hashCode());
        assertEquals(row(MENU_ATTACK), row(MENU_ATTACK));
        assertNotEquals(row(MENU_ATTACK), row(MENU_EXAMINE));
        assertEquals(MenuAction.CANCEL, row(MENU_CANCEL).getType());
    }

    @Test
    void readsTheRowsTheClientRebuilt() {
        game.setMenuRows(MENU_WALK, MENU_CANCEL);
        MenuEntry[] entries = client.getMenuEntries();
        assertEquals(2, entries.length);
        assertEquals("Walk here", entries[1].getOption());
    }
}
