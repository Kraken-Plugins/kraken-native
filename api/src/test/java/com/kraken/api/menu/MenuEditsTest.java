package com.kraken.api.menu;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Menu;
import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.menu.MenuClick;
import com.kraken.api.internal.menu.MenuHook;
import com.kraken.api.internal.menu.WireRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.MENU_ATTACK;
import static com.kraken.api.GameFixture.MENU_CANCEL;
import static com.kraken.api.GameFixture.MENU_CHOP;
import static com.kraken.api.GameFixture.MENU_DROP;
import static com.kraken.api.GameFixture.MENU_EXAMINE;
import static com.kraken.api.GameFixture.MENU_PLUGIN_ROW;
import static com.kraken.api.GameFixture.MENU_WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MenuEdits} over the {@link GameFixture}: what a plugin's rule, an entry setter, a created entry and
 * {@code setMenuEntries} send to the hook, how clicks and expiries come back, and what a reconnect re-sends.
 */
class MenuEditsTest {

    private static final int KIND_NPC = 2;
    private static final int KIND_LOC = 3;
    private static final int KIND_COMPONENT = 7;

    private GameFixture game;
    private Client client;
    private MenuEdits edits;
    private final List<String> sent = new ArrayList<>();
    private final List<WireRule> rules = new ArrayList<>();

    private final MenuHook hook = new MenuHook() {
        @Override
        public void replace(List<WireRule> all) {
            sent.add("replace " + ids(all));
            rules.clear();
            rules.addAll(all);
        }

        @Override
        public void upsert(List<WireRule> some) {
            sent.add("upsert " + ids(some));
            for (WireRule rule : some) {
                rules.removeIf(r -> r.id() == rule.id());
                rules.add(rule);
            }
        }

        @Override
        public void remove(int[] ids) {
            sent.add("remove " + Arrays.toString(ids));
            for (int id : ids) {
                rules.removeIf(r -> r.id() == id);
            }
        }
    };

    private static List<Integer> ids(List<WireRule> rules) {
        return rules.stream().map(WireRule::id).toList();
    }

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        edits = client.getMenuEdits();
        edits.setHook(hook);
        sent.clear();
    }

    private MenuEntry row(int row) {
        MenuEntry[] entries = client.getMenuEntries();
        return entries[entries.length - 1 - row];
    }

    private WireRule rule(int id) {
        return rules.stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    @Test
    void theSeedMappingSupportsEdits() {
        assertTrue(edits.isSupported());
        assertTrue(edits.isAvailable());
        edits.setHook(null);
        assertFalse(edits.isAvailable());
    }

    @Test
    void aSwapRuleIsCompiledToTheMappingsNumbersAndSentOnFlush() {
        int id = edits.add(MenuRule.Swap.options("Talk-to", "<col=ff>Bank", ShiftState.HELD));
        assertEquals(List.of(), sent, "nothing goes until the flush");
        edits.flush();
        assertEquals(List.of("upsert [" + id + "]"), sent);
        WireRule wire = rule(id);
        assertEquals(WireRule.Type.SWAP, wire.type());
        assertEquals(ShiftState.HELD, wire.shift());
        assertFalse(wire.scoped());
        assertEquals(Set.of(WireRule.Match.Field.OPTION), wire.match().mask());
        assertEquals(new TextMatch(TextMatch.Mode.EXACT, "talk-to"), wire.match().option());
        assertEquals(new TextMatch(TextMatch.Mode.EXACT, "bank"), wire.swapped().option(), "tags stripped, lower-cased");
        assertTrue(wire.sameTarget());
        assertTrue(wire.below());
        assertFalse(wire.toTop());
    }

    @Test
    void matchesNameKindsOpsGroupsAndTilesAsTheEntriesHoldThem() {
        WireRule.Match npc = edits.compile(MenuMatch.any().action(MenuAction.NPC_THIRD_OPTION).typeId(3029));
        assertEquals(Set.of(WireRule.Match.Field.KIND, WireRule.Match.Field.OP, WireRule.Match.Field.TYPE_ID),
                npc.mask());
        assertEquals(KIND_NPC, npc.kind());
        assertEquals(3, npc.op());
        assertEquals(3029, npc.typeId());

        WireRule.Match op = edits.compile(MenuMatch.any().action(MenuAction.CC_OP, 3).group(149));
        assertEquals(KIND_COMPONENT, op.kind());
        assertEquals(2, op.op(), "a component op's index counts from 0");
        assertEquals(149, op.group());
        assertTrue(op.mask().contains(WireRule.Match.Field.GROUP));

        WireRule.Match anyOp = edits.compile(MenuMatch.any().action(MenuAction.CC_OP));
        assertFalse(anyOp.mask().contains(WireRule.Match.Field.OP), "an op number left out matches any op");

        WireRule.Match examine = edits.compile(MenuMatch.any().action(MenuAction.EXAMINE_OBJECT));
        assertEquals(KIND_LOC, examine.kind());
        assertEquals(game.map.enumValue("minimenuOp", "EXAMINE", 0), examine.op());

        WireRule.Match walk = edits.compile(MenuMatch.any().action(MenuAction.WALK).tile(new WorldPoint(1, 2, 0)));
        assertEquals(Set.of(WireRule.Match.Field.KIND, WireRule.Match.Field.TILE), walk.mask());
        assertEquals(1, walk.x());
        assertEquals(2, walk.z());

        WireRule.Match cancel = edits.compile(MenuMatch.any().action(MenuAction.CANCEL));
        assertEquals(0, cancel.kind());
        assertEquals(new TextMatch(TextMatch.Mode.EXACT, "cancel"), cancel.option());

        WireRule.Match sub = edits.compile(MenuMatch.any().option(TextMatch.contains("rub")).inSubMenus());
        assertTrue(sub.subRows());
    }

    @Test
    void anEntrysIdentityIsItsSubjectAndOpWithoutTheTexts() {
        MenuMatch attack = MenuMatch.of(row(MENU_ATTACK));
        assertEquals(MenuSubject.NPC, attack.kind());
        assertEquals(MenuAction.NPC_SECOND_OPTION, attack.action());
        assertEquals(5, attack.index());
        assertNull(attack.option());
        assertEquals(MenuMatch.of(row(MENU_ATTACK)), attack);

        MenuMatch chop = MenuMatch.of(row(MENU_CHOP));
        assertEquals(MenuSubject.LOC, chop.kind());
        assertEquals(new WorldPoint(BASE_X + 5, BASE_Z + 5, 0), chop.tile());
        MenuMatch chopSubject = MenuMatch.subject(row(MENU_CHOP));
        assertNull(chopSubject.action(), "a subject match takes any op");
        assertEquals(chop.tile(), chopSubject.tile());

        MenuMatch drop = MenuMatch.of(row(MENU_DROP));
        assertEquals(MenuSubject.COMPONENT, drop.kind());
        assertEquals(3, drop.identifier());
        assertEquals(149 << 16, drop.typeId());
        assertEquals(0, drop.index());

        MenuMatch cancel = MenuMatch.of(row(MENU_CANCEL));
        assertEquals(MenuSubject.NONE, cancel.kind());
        assertEquals(new TextMatch(TextMatch.Mode.EXACT, "cancel"), cancel.option());
    }

    @Test
    void entrySettersBecomeOneScopedEditRuleOnTheRow() {
        MenuEntry attack = row(MENU_ATTACK);
        attack.setTarget("<col=ff0000>Goblin");
        attack.setOption("Fight");
        edits.flush();
        assertEquals(1, sent.size(), "both setters go in one rule");
        int id = edits.ids().get(0);
        WireRule wire = rule(id);
        assertEquals(WireRule.Type.EDIT, wire.type());
        assertTrue(wire.scoped());
        assertEquals(KIND_NPC, wire.match().kind());
        assertEquals(5, wire.match().index());
        assertEquals(2, wire.match().op());
        assertEquals(Set.of(MenuRule.Edit.Action.SET_OPTION, MenuRule.Edit.Action.SET_TARGET), wire.actions());
        assertEquals("Fight", wire.option());
        assertEquals("<col=ff0000>Goblin", wire.target());

        attack.setDeprioritized(true);
        assertTrue(attack.isDeprioritized());
        assertTrue(row(MENU_ATTACK).isDeprioritized(), "a later read of the same row sees its rule");
        edits.flush();
        assertEquals(id, edits.ids().get(0), "the same rule, changed");
        assertTrue(rule(id).actions().contains(MenuRule.Edit.Action.DEPRIORITIZE));

        attack.setDeprioritized(false);
        edits.flush();
        assertFalse(rule(id).actions().contains(MenuRule.Edit.Action.DEPRIORITIZE));
    }

    @Test
    void aRuleWithNoActionsLeftIsRemoved() {
        MenuEntry walk = row(MENU_WALK);
        walk.setDeprioritized(true);
        edits.flush();
        int id = edits.ids().get(0);
        walk.setDeprioritized(false);
        edits.flush();
        assertEquals(List.of("upsert [" + id + "]", "remove [" + id + "]"), sent);
        assertEquals(List.of(), edits.ids());
    }

    @Test
    void onClickOnAClientRowReportsItsClicksToTheCallback() {
        List<MenuEntry> clicked = new ArrayList<>();
        MenuEntry chop = row(MENU_CHOP);
        chop.onClick(clicked::add);
        edits.flush();
        int id = edits.ids().get(0);
        assertEquals(Set.of(MenuRule.Edit.Action.REPORT), rule(id).actions());
        assertNull(edits.clicked(new MenuClick(true, id, -1, 10, 20)), "the click tracker posts the client's run");
        assertEquals(List.of(chop), clicked);
        assertNull(edits.clicked(new MenuClick(true, 999, -1, 0, 0)), "an unknown rule");
    }

    @Test
    void aCreatedEntryIsAnAddRuleUnderItsAnchorWithItsSubMenu() {
        List<String> clicks = new ArrayList<>();
        MenuEntry[] entries = client.getMenuEntries();
        int examineIdx = entries.length - 1 - MENU_EXAMINE;
        MenuEntry swap = client.createMenuEntry(examineIdx).setOption("Swap left-click").setTarget("<col=ffff00>Goblin")
                .setType(MenuAction.RUNELITE).onClick(e -> clicks.add("swap"));
        Menu sub = swap.createSubMenu();
        MenuEntry first = sub.createMenuEntry(0).setOption("Attack").onClick(e -> clicks.add("attack:" + e.getOption()));
        MenuEntry second = sub.createMenuEntry(0).setOption("Walk here").onClick(e -> clicks.add("walk"));
        assertEquals(List.of(), sent, "sent on flush, once the chain is done");
        edits.flush();
        assertEquals(1, sent.size());
        int id = edits.ids().get(0);
        WireRule wire = rule(id);
        assertEquals(WireRule.Type.ADD, wire.type());
        assertTrue(wire.scoped());
        assertEquals(MenuRule.Add.Position.BELOW, wire.position());
        assertEquals(KIND_NPC, wire.match().kind());
        assertEquals(game.map.enumValue("minimenuOp", "EXAMINE", 0), wire.match().op(), "anchored to the Examine row");
        assertEquals(swap.getRowId(), wire.row().id());
        assertEquals("Swap left-click", wire.row().option());
        assertEquals("<col=ffff00>Goblin", wire.row().target());
        assertEquals(List.of(first.getRowId(), second.getRowId()),
                wire.row().children().stream().map(WireRule.Row::id).toList(), "rows created at 0 read top to bottom");
        assertEquals(List.of(second, first), List.of(sub.getMenuEntries()), "bottom first");

        assertSame(swap, edits.created(swap.getRowId()));
        assertSame(first, edits.created(first.getRowId()));
        assertSame(first, edits.clicked(new MenuClick(false, first.getRowId(), swap.getRowId(), 1, 2)));
        assertSame(swap, edits.clicked(new MenuClick(false, swap.getRowId(), -1, 1, 2)));
        assertEquals(List.of("attack:Attack", "swap"), clicks);

        first.setOption("Fight");
        edits.flush();
        assertEquals(2, sent.size(), "a changed sub row sends the top row's rule again");
        assertEquals("Fight", rule(id).row().children().get(0).option());

        edits.expired(id);
        assertNull(edits.created(swap.getRowId()));
        assertNull(edits.created(first.getRowId()));
        assertEquals(List.of(), edits.ids());
    }

    @Test
    void aCreatedEntryAtMinusOneGoesOnTop() {
        MenuEntry top = client.createMenuEntry(-1).setOption("Mark tile");
        edits.flush();
        WireRule wire = rule(edits.ids().get(0));
        assertEquals(MenuRule.Add.Position.TOP, wire.position());
        assertEquals(KIND_NPC, wire.match().kind(), "anchored to the top row, the Attack");
        assertEquals(MenuAction.RUNELITE, top.getType());
        assertFalse(top.equals(client.createMenuEntry(-1).setOption("Mark tile")), "created rows are distinct");
        assertThrows(IllegalStateException.class, () -> row(MENU_WALK).setType(MenuAction.RUNELITE));
        assertThrows(IllegalStateException.class, () -> row(MENU_WALK).createSubMenu());
    }

    @Test
    void aPluginsRowReadsAsTheCreatedEntry() {
        MenuEntry mark = client.createMenuEntry(-1).setOption("Mark tile").setIdentifier(7).setItemId(995);
        game.setPluginRow(mark.getRowId(), "Mark tile", "");
        game.setMenuRows(MENU_PLUGIN_ROW, MENU_CANCEL);
        MenuEntry[] entries = client.getMenuEntries();
        assertEquals(2, entries.length);
        assertSame(mark, entries[1]);
        assertEquals(7, entries[1].getIdentifier());
        assertEquals(995, entries[1].getItemId());

        game.setPluginRow(4242, "Gone", "");
        MenuEntry stale = client.getMenuEntries()[1];
        assertEquals(MenuAction.RUNELITE, stale.getType(), "a row of a plugin no longer known still reads as one");
        assertEquals(4242, stale.getIdentifier());
        assertEquals("Gone", stale.getOption());
    }

    @Test
    void setMenuEntriesHidesRowsLeftOutAndPromotesANewTop() {
        MenuEntry[] entries = client.getMenuEntries();
        List<MenuEntry> wanted = new ArrayList<>(List.of(entries));
        MenuEntry examine = row(MENU_EXAMINE);
        wanted.remove(examine);
        MenuEntry chop = row(MENU_CHOP);
        wanted.remove(chop);
        wanted.add(chop);
        client.setMenuEntries(wanted.toArray(new MenuEntry[0]));
        edits.flush();
        assertEquals(2, edits.ids().size());
        WireRule hide = rule(edits.ids().get(0));
        assertEquals(Set.of(MenuRule.Edit.Action.HIDE), hide.actions());
        assertEquals(game.map.enumValue("minimenuOp", "EXAMINE", 0), hide.match().op());
        WireRule promote = rule(edits.ids().get(1));
        assertEquals(Set.of(MenuRule.Edit.Action.PROMOTE), promote.actions());
        assertEquals(KIND_LOC, promote.match().kind());
    }

    @Test
    void aPersistentAddRuleMakesEntriesForItsRowsClicks() {
        List<String> clicks = new ArrayList<>();
        MenuRow child = MenuRow.of("Red", "", e -> clicks.add("red:" + e.getOption()));
        int id = edits.add(new MenuRule.Add(MenuMatch.any().action(MenuAction.WALK), ShiftState.HELD, false,
                MenuRule.Add.Position.ABOVE, new MenuRow("Color", "<col=00ff00>Tile", e -> clicks.add("color"),
                List.of(child))));
        edits.flush();
        WireRule wire = rule(id);
        assertEquals("Color", wire.row().option());
        assertEquals(1, wire.row().children().size());
        int childId = wire.row().children().get(0).id();
        MenuEntry posted = edits.clicked(new MenuClick(false, childId, wire.row().id(), 0, 0));
        assertNotNull(posted);
        assertEquals("Red", posted.getOption());
        assertEquals(MenuAction.RUNELITE, posted.getType());
        assertSame(posted, edits.created(childId));
        assertEquals(List.of("red:Red"), clicks);
        edits.remove(id);
        edits.flush();
        assertEquals("remove [" + id + "]", sent.get(sent.size() - 1));
        assertNull(edits.created(childId));
    }

    @Test
    void aReconnectSendsEveryRuleAgainAndRulesWaitWithoutAHook() {
        int swap = edits.add(MenuRule.Swap.options("Talk-to", "Bank", ShiftState.ANY));
        row(MENU_ATTACK).setDeprioritized(true);
        edits.flush();
        edits.setHook(null);
        int later = edits.add(MenuRule.Edit.of(MenuMatch.any().action(MenuAction.WALK), MenuRule.Edit.Action.HIDE));
        edits.flush();
        sent.clear();
        edits.setHook(hook);
        assertEquals(List.of("replace " + edits.ids()), sent);
        assertEquals(List.of(swap, edits.ids().get(1), later), edits.ids(), "in the order they apply");
        assertEquals(3, rules.size());
    }
}
