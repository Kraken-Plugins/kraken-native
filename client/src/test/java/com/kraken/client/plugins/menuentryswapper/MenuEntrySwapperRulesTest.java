package com.kraken.client.plugins.menuentryswapper;

import com.kraken.api.MenuAction;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.menu.MenuMatch;
import com.kraken.api.menu.MenuRule;
import com.kraken.api.menu.MenuRule.Edit.Action;
import com.kraken.api.menu.MenuSubject;
import com.kraken.api.menu.ShiftState;
import com.kraken.api.menu.TextMatch;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu rules the swapper builds from its settings and the swaps stored per item, NPC, object and component.
 */
class MenuEntrySwapperRulesTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager configManager;
    private MenuEntrySwapperConfig config;
    private MenuEntrySwapperRules rules;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        configManager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        config = configManager.getConfig(MenuEntrySwapperConfig.class);
        rules = new MenuEntrySwapperRules(config, id -> id == 379 ? "Lobster" : id == 995 ? "Coins" : null);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private void set(String key, Object value) {
        configManager.setConfiguration(MenuEntrySwapperConfig.GROUP, key, value);
    }

    private static List<MenuRule.Swap> swaps(List<MenuRule> all) {
        return all.stream().filter(MenuRule.Swap.class::isInstance).map(MenuRule.Swap.class::cast).toList();
    }

    private static MenuRule.Edit edit(List<MenuRule> all, int index) {
        return (MenuRule.Edit) all.stream().filter(MenuRule.Edit.class::isInstance).toList().get(index);
    }

    @Test
    void theDefaultSettingsMakeTheBankTradeAndQuickSwaps() {
        List<MenuRule> all = rules.rules(Map.of());
        List<MenuRule.Swap> swaps = swaps(all);
        MenuRule.Swap bank = swaps.stream().filter(s -> s.swapped().option().equals(TextMatch.exact("bank"))
                && s.trigger().option().equals(TextMatch.exact("talk-to"))).findFirst().orElseThrow();
        assertNull(bank.trigger().target(), "any banker");
        assertTrue(bank.sameTarget());
        assertTrue(bank.below());
        assertFalse(bank.toTop());
        assertEquals(ShiftState.ANY, bank.shift());
        int assignment = swaps.indexOf(swaps.stream().filter(s -> s.swapped().option().text().equals("assignment"))
                .findFirst().orElseThrow());
        int trade = swaps.indexOf(swaps.stream().filter(s -> s.swapped().option().text().equals("trade"))
                .findFirst().orElseThrow());
        assertTrue(assignment < trade, "assignment takes priority over trade for slayer masters");
        assertTrue(swaps.stream().anyMatch(s -> s.trigger().target() != null
                && s.trigger().target().text().equals("mage of zamorak")), "a swap on one NPC names its target");
        assertTrue(swaps.stream().anyMatch(s -> s.swapped().option().equals(TextMatch.contains("last-destination"))),
                "the fairy ring's default swap");
        assertFalse(swaps.stream().anyMatch(s -> s.swapped().option().text().equals("harpoon")), "off by default");
        assertTrue(swaps.stream().anyMatch(s -> s.trigger().target() != null
                && s.trigger().target().equals(TextMatch.suffix("birdhouse"))), "birdhouses match by suffix");
        assertTrue(all.stream().noneMatch(MenuRule.Edit.class::isInstance), "no Walk here or bank rules by default");
    }

    @Test
    void shiftOnlySwapsAndSettingsFollowTheShiftKey() {
        set("shopBuy", BuyMode.BUY_10);
        set("swapGEAbort", true);
        set("swapStairsLeftClick", MenuEntrySwapperConfig.StairsMode.CLIMB_UP);
        set("swapStairsShiftClick", MenuEntrySwapperConfig.StairsMode.CLIMB_DOWN);
        set("npcShiftClickWalkHere", true);
        set("groundItemShiftClickWalkHere", true);
        List<MenuRule> all = rules.rules(Map.of());
        List<MenuRule.Swap> swaps = swaps(all);
        MenuRule.Swap buy = swaps.stream().filter(s -> s.swapped().option().text().equals("buy 10")).findFirst()
                .orElseThrow();
        assertEquals(ShiftState.HELD, buy.shift());
        assertFalse(swaps.stream().anyMatch(s -> s.swapped().option().text().equals("buy 5")));
        assertEquals(ShiftState.HELD, swaps.stream().filter(s -> s.swapped().option().text().equals("abort offer"))
                .findFirst().orElseThrow().shift());
        assertEquals(ShiftState.NOT_HELD, swaps.stream().filter(s -> s.swapped().option().text().equals("climb-up"))
                .findFirst().orElseThrow().shift());
        assertEquals(ShiftState.HELD, swaps.stream().filter(s -> s.swapped().option().text().equals("climb-down"))
                .findFirst().orElseThrow().shift());

        List<MenuRule.Edit> edits = all.stream().filter(MenuRule.Edit.class::isInstance).map(MenuRule.Edit.class::cast)
                .toList();
        assertEquals(2, edits.size());
        assertEquals(MenuSubject.NPC, edits.get(0).match().kind());
        assertEquals(Set.of(Action.DEPRIORITIZE), edits.get(0).actions());
        assertEquals(ShiftState.HELD, edits.get(0).shift());
        assertEquals(MenuSubject.OBJ, edits.get(1).match().kind());
    }

    @Test
    void bankShiftClicksPromoteTheChosenOpOnEachStorageInterface() {
        set("bankWithdrawShiftClick", ShiftWithdrawMode.WITHDRAW_ALL_BUT_1);
        set("bankDepositShiftClick", ShiftDepositMode.DEPOSIT_ALL);
        List<MenuRule> all = rules.rules(Map.of());
        List<MenuRule.Edit> edits = all.stream().filter(MenuRule.Edit.class::isInstance).map(MenuRule.Edit.class::cast)
                .toList();
        assertEquals(7, edits.size());
        assertEquals(MenuMatch.any().group(InterfaceID.BANKMAIN).action(MenuAction.CC_OP, 8), edits.get(0).match());
        assertEquals(Set.of(Action.PROMOTE), edits.get(0).actions());
        assertEquals(ShiftState.HELD, edits.get(0).shift());
        assertEquals(MenuMatch.any().group(InterfaceID.RAIDS_STORAGE_PRIVATE).action(MenuAction.CC_OP, 4),
                edits.get(1).match(), "a storage unit has no all-but-one, so withdraw all");
        assertEquals(MenuMatch.any().group(InterfaceID.BANKSIDE).action(MenuAction.CC_OP, 8), edits.get(3).match());
        assertEquals(MenuMatch.any().group(InterfaceID.BANK_DEPOSITBOX).action(MenuAction.CC_OP, 6),
                edits.get(4).match());
        assertEquals(MenuMatch.any().group(InterfaceID.SHARED_BANK_SIDE).action(MenuAction.CC_OP, 7),
                edits.get(5).match());
        assertEquals(MenuMatch.any().group(InterfaceID.RAIDS_STORAGE_SIDE).action(MenuAction.CC_OP, 4),
                edits.get(6).match());

        set("bankDepositShiftClick", ShiftDepositMode.EXTRA_OP);
        List<MenuRule> extra = rules.rules(Map.of());
        assertEquals(5, extra.stream().filter(MenuRule.Edit.class::isInstance).count(),
                "the extra op exists only on the bank side and deposit box");
    }

    @Test
    void storedSwapsBecomeRulesOnTheirSubjects() {
        String g = MenuEntrySwapperConfig.GROUP + ".";
        MenuRule.Edit npc = (MenuRule.Edit) rules.storedRule(g + "npc_3029", "2");
        assertEquals(MenuMatch.any().kind(MenuSubject.NPC).typeId(3029).action(MenuAction.NPC_THIRD_OPTION),
                npc.match());
        assertEquals(Set.of(Action.PROMOTE_SUBJECT), npc.actions());
        assertEquals(ShiftState.NOT_HELD, npc.shift());
        MenuRule.Edit npcWalk = (MenuRule.Edit) rules.storedRule(g + "npc_shift_3029", "-1");
        assertEquals(MenuMatch.any().kind(MenuSubject.NPC).typeId(3029), npcWalk.match());
        assertEquals(Set.of(Action.DEPRIORITIZE), npcWalk.actions());
        assertEquals(ShiftState.HELD, npcWalk.shift());

        MenuRule.Edit object = (MenuRule.Edit) rules.storedRule(g + "object_1276", "1");
        assertEquals(MenuMatch.any().kind(MenuSubject.LOC).typeId(1276).action(MenuAction.GAME_OBJECT_SECOND_OPTION),
                object.match());
        assertEquals(Set.of(Action.PROMOTE), object.actions());
        assertNull(rules.storedRule(g + "object_1276", "257"), "a sub-option swap isn't applied");

        MenuRule.Edit item = (MenuRule.Edit) rules.storedRule(g + "item_379", "0");
        assertEquals(MenuMatch.any().group(InterfaceID.INVENTORY).target(TextMatch.exact("Lobster"))
                .action(MenuAction.CC_OP, 2), item.match(), "the first item action is component op 2");
        assertEquals(ShiftState.NOT_HELD, item.shift());
        MenuRule.Edit drop = (MenuRule.Edit) rules.storedRule("shiftclick.item_379", "4");
        assertEquals(8, drop.match().identifier(), "the fifth item action is component op 8");
        assertEquals(ShiftState.HELD, drop.shift());
        MenuRule.Edit use = (MenuRule.Edit) rules.storedRule(g + "item_379", "-1");
        assertEquals(MenuAction.WIDGET_TARGET, use.match().action());
        assertEquals(Set.of(Action.PROMOTE), use.actions());
        assertNull(rules.storedRule(g + "item_1", "0"), "an item whose name can't be read");
        assertNull(rules.storedRule(g + "item_379", "" + "Rub".hashCode()), "a sub-option swap isn't applied");

        MenuRule.Edit worn = (MenuRule.Edit) rules.storedRule(g + "wornitem_shift_995", "3");
        assertEquals(MenuMatch.any().group(InterfaceID.WORNITEMS).target(TextMatch.exact("Coins"))
                .action(MenuAction.CC_OP, 3), worn.match());
        assertEquals(ShiftState.HELD, worn.shift());

        MenuRule.Edit ui = (MenuRule.Edit) rules.storedRule(g + "ui_786445", "2");
        assertEquals(MenuMatch.any().typeId(786445).action(MenuAction.CC_OP, 2), ui.match());
        MenuRule.Edit uiItem = (MenuRule.Edit) rules.storedRule(g + "ui_shift_786445_995", "3");
        assertEquals(MenuMatch.any().typeId(786445).action(MenuAction.CC_OP, 3).target(TextMatch.exact("Coins")),
                uiItem.match());
        assertEquals(ShiftState.HELD, uiItem.shift());

        assertNull(rules.storedRule(g + "swapBank", "true"), "a setting that isn't a swap");
        assertNull(rules.storedRule(g + "npc_x", "1"));

        set("leftClickCustomization", false);
        assertNull(rules.storedRule(g + "item_379", "0"), "item swaps need their setting on");
    }

    @Test
    void storedSwapsComeBeforeTheBuiltInOnes() {
        List<MenuRule> all = rules.rules(Map.of(MenuEntrySwapperConfig.GROUP + ".npc_1", "0"));
        assertTrue(all.get(0) instanceof MenuRule.Edit);
        assertEquals(Set.of(Action.PROMOTE_SUBJECT), edit(all, 0).actions());
        assertTrue(all.get(1) instanceof MenuRule.Swap);
        assertEquals(-1, MenuEntrySwapperPlugin.defaultOp(new String[]{null, "", null, "Eat", "Drop"}));
        assertEquals(1, MenuEntrySwapperPlugin.defaultOp(new String[]{null, "Eat", null, null, "Drop"}));
    }
}
