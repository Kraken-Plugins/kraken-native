package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarClientID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Bank}: the bank's contents from the bank table, its item widgets for withdrawals (placeholders skipped), the
 * deposits through the side panel, the deposit buttons, and the Withdraw-X count prompt answered by a client task.
 */
class BankTest {

    private static final int BANK = InterfaceID.Bankmain.UNIVERSE >>> 16;
    private static final int UNIVERSE = InterfaceID.Bankmain.UNIVERSE;
    private static final int INFINITE = InterfaceID.Bankmain.INFINITE;
    private static final int ITEMS = InterfaceID.Bankmain.ITEMS;
    private static final int DEPOSIT_INVENTORY = InterfaceID.Bankmain.DEPOSITINV;
    private static final int DEPOSIT_WORN = InterfaceID.Bankmain.DEPOSITWORN;
    private static final int SIDE_GROUP = InterfaceID.Bankside.UNIVERSE >>> 16;
    private static final int SIDE_ROOT = InterfaceID.Bankside.UNIVERSE;
    private static final int SIDE_ITEMS = InterfaceID.Bankside.ITEMS;

    private static final int COINS = 995;
    private static final int LOGS = 1511;
    private static final int WHIP = 4151;

    private static final String[] WITHDRAW_OPS = {
            "Withdraw-1", "Withdraw-5", "Withdraw-10", "Withdraw-X", "Withdraw-All"};
    private static final String[] DEPOSIT_OPS = {"Deposit-1", "Deposit-5", "Deposit-10", "Deposit-X", "Deposit-All"};

    /**
     * The op numbers of the Withdraw-X and Withdraw-All ops in both lists, 1-based.
     */
    private static final int X = 4;
    private static final int ALL = 5;

    private GameFixture game;
    private Client client;
    private Bank bank;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();
    private final List<BooleanSupplier> tasks = new ArrayList<>();
    private long depositWorn;

    /**
     * A bank slot: an item and its quantity, 0 for a placeholder.
     *
     * @param item     the item's id
     * @param quantity the stack size
     */
    private record Slot(int item, int quantity) {
    }

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        client.setTaskQueue(tasks::add);
        bank = client.getBank();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 0));
        game.setTick(100);
        game.setInvSlot(95, 0, COINS, 100);
        game.setInvSlot(95, 1, LOGS, 3);
    }

    /**
     * Builds the bank's main interface, loaded but not open, with the given item slots in order.
     *
     * @param slots the slots, sub id 0 first
     */
    private void buildBank(Slot... slots) {
        long[] children = new long[(DEPOSIT_WORN & 0xffff) + 1];
        for (int i = 0; i < children.length; i++) {
            children[i] = game.addComponent((BANK << 16) | i, -1, -1, -1, WidgetType.LAYER, 0, 0, 0, 0);
        }
        children[0] = game.addComponent(INFINITE, -1, -1, -1, WidgetType.LAYER, 0, 0, 480, 314);
        children[1] = game.addComponent(UNIVERSE, -1, -1, INFINITE, WidgetType.LAYER, 0, 0, 480, 314);
        long items = game.addComponent(ITEMS, -1, -1, UNIVERSE, WidgetType.LAYER, 0, 0, 480, 250);
        children[ITEMS & 0xffff] = items;
        children[DEPOSIT_INVENTORY & 0xffff] = button(DEPOSIT_INVENTORY, "Deposit inventory");
        depositWorn = button(DEPOSIT_WORN, "Deposit worn items");
        children[DEPOSIT_WORN & 0xffff] = depositWorn;

        long[] shown = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            shown[i] = slot(ITEMS, i, slots[i], WITHDRAW_OPS);
        }
        game.setDynamicChildren(items, shown);
        game.setGroup(BANK, children);
    }

    private long button(int packedId, String op) {
        long button = game.addComponent(packedId, -1, -1, UNIVERSE, WidgetType.GRAPHIC, 0, 0, 20, 20);
        game.setOps(button, op);
        return button;
    }

    private long slot(int packedId, int index, Slot slot, String[] ops) {
        long c = game.addComponent(packedId, index, -1, packedId, WidgetType.GRAPHIC, 0, 0, 36, 32);
        game.memory.putInt(c + game.map.offset("IfType.linkObjType"), slot.item());
        game.memory.putInt(c + game.map.offset("IfType.linkObjCount"), slot.quantity());
        game.setOps(c, ops);
        return c;
    }

    /**
     * Builds the bank's main interface with the given slots and opens it.
     *
     * @param slots the slots, sub id 0 first
     */
    private void openBank(Slot... slots) {
        buildBank(slots);
        game.openGroup(BANK, 0, 0);
    }

    /**
     * Builds the inventory side panel the bank shows while open, holding 100 coins in slot 0, and opens its group.
     */
    private void openSidePanel() {
        long universe = game.addComponent(SIDE_ROOT, -1, -1, -1, WidgetType.LAYER, 0, 0, 190, 261);
        long container = game.addComponent(SIDE_GROUP << 16 | 1, -1, -1, SIDE_ROOT, WidgetType.LAYER, 0, 0, 190, 261);
        long background = game.addComponent((SIDE_GROUP << 16) | 2, -1, -1, SIDE_ROOT, WidgetType.GRAPHIC, 0, 0, 190,
                261);
        long items = game.addComponent(SIDE_ITEMS, -1, -1, SIDE_ROOT, WidgetType.LAYER, 0, 0, 190, 261);
        long coins = slot(SIDE_ITEMS, 0, new Slot(COINS, 100), DEPOSIT_OPS);
        game.setGroup(SIDE_GROUP, universe, container, background, items);
        game.setDynamicChildren(items, coins);
        game.openGroup(SIDE_GROUP, 0, 0);
    }

    private void closeBank() {
        game.closeGroup(BANK);
    }

    private void runTasks() {
        tasks.removeIf(BooleanSupplier::getAsBoolean);
    }

    @Test
    void isOpenFollowsTheInterfaceOnScreen() {
        buildBank();
        assertFalse(bank.isOpen(), "loaded, not open");
        game.openGroup(BANK, 0, 0);
        assertTrue(bank.isOpen());
        closeBank();
        assertFalse(bank.isOpen(), "closed, though the client keeps it loaded");
    }

    @Test
    void contentsComeFromTheBankTable() {
        assertTrue(bank.contains(COINS));
        assertEquals(100, bank.count(COINS));
        assertEquals(3, bank.count(LOGS));
        assertFalse(bank.contains(WHIP));
        assertEquals(0, bank.count(WHIP));
    }

    @Test
    void withdrawRunsTheNamedAmountOp() {
        openBank(new Slot(LOGS, 0), new Slot(COINS, 100), new Slot(LOGS, 3));
        assertEquals(ActionResult.SENT, bank.withdraw(COINS, 5));
        reference.menuAction(1, ITEMS, MenuAction.CC_OP, 2, COINS);
        assertEquals(expected, sent);
        assertTrue(tasks.isEmpty(), "a named amount needs no count prompt");
    }

    @Test
    void placeholdersAreSkipped() {
        openBank(new Slot(LOGS, 0), new Slot(COINS, 100), new Slot(LOGS, 3));
        assertEquals(ActionResult.SENT, bank.withdraw(LOGS, 1));
        reference.menuAction(2, ITEMS, MenuAction.CC_OP, 1, LOGS);
        assertEquals(expected, sent, "the stocked slot, not the placeholder before it");
    }

    @Test
    void aPlaceholderAloneIsNotFound() {
        openBank(new Slot(LOGS, 0));
        assertEquals(ActionResult.NOT_FOUND, bank.withdraw(LOGS, 1));
        assertEquals(ActionResult.NOT_FOUND, bank.withdrawAll(LOGS));
        assertTrue(sent.isEmpty());
    }

    @Test
    void anAmountCoveringTheStackRunsAll() {
        openBank(new Slot(LOGS, 0), new Slot(COINS, 100), new Slot(LOGS, 3));
        assertEquals(ActionResult.SENT, bank.withdraw(LOGS, 3), "Withdraw-3 isn't an op, the stack is 3");
        reference.menuAction(2, ITEMS, MenuAction.CC_OP, ALL, LOGS);
        assertEquals(ActionResult.SENT, bank.withdrawAll(COINS));
        reference.menuAction(1, ITEMS, MenuAction.CC_OP, ALL, COINS);
        assertEquals(expected, sent);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void withdrawXAnswersTheCountPromptOnceItOpens() {
        openBank(new Slot(COINS, 100));
        assertEquals(ActionResult.SENT, bank.withdraw(COINS, 7));
        reference.menuAction(0, ITEMS, MenuAction.CC_OP, X, COINS);
        assertEquals(expected, sent);
        assertEquals(1, tasks.size(), "the follow-up waits for the prompt");

        runTasks();
        assertEquals(1, tasks.size(), "no prompt yet");
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 7));
        runTasks();
        reference.resumeCountDialog(7);
        assertEquals(expected, sent, "the count answered once, with the amount");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void aPromptAlreadyOpenWhenTheOpIsSentIsLeftAlone() {
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 7));
        openBank(new Slot(COINS, 100));
        assertEquals(ActionResult.SENT, bank.withdraw(COINS, 7));
        runTasks();
        assertEquals(1, tasks.size(), "still waiting for the prompt to close");
        assertEquals(1, sent.size(), "the open prompt isn't answered");

        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 0));
        runTasks();
        assertEquals(1, tasks.size());
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 7));
        runTasks();
        reference.menuAction(0, ITEMS, MenuAction.CC_OP, X, COINS);
        reference.resumeCountDialog(7);
        assertEquals(expected, sent, "only the prompt the op opened is answered");
        assertTrue(tasks.isEmpty());
    }

    @Test
    void withdrawXGivesUpAfterFiveTicks() {
        openBank(new Slot(COINS, 100));
        assertEquals(ActionResult.SENT, bank.withdraw(COINS, 7));
        game.setTick(104);
        runTasks();
        assertEquals(1, tasks.size(), "four ticks in, still waiting");
        game.setTick(105);
        runTasks();
        assertTrue(tasks.isEmpty(), "gave up after five ticks");
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 7));
        runTasks();
        assertEquals(1, sent.size(), "a prompt opening after giving up isn't answered");
    }

    @Test
    void withdrawXNotFoundWhileTheBankIsClosed() {
        buildBank(new Slot(COINS, 100));
        assertEquals(ActionResult.NOT_FOUND, bank.withdraw(COINS, 7));
        assertTrue(sent.isEmpty());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void nothingIsSentWhileTheBankIsClosed() {
        buildBank(new Slot(COINS, 100));
        openSidePanel();
        assertEquals(ActionResult.NOT_FOUND, bank.withdraw(COINS, 5));
        assertEquals(ActionResult.NOT_FOUND, bank.withdrawAll(COINS));
        assertEquals(ActionResult.NOT_FOUND, bank.deposit(COINS, 5));
        assertEquals(ActionResult.NOT_FOUND, bank.depositAll(COINS));
        assertEquals(ActionResult.NOT_FOUND, bank.depositInventory());
        assertEquals(ActionResult.NOT_FOUND, bank.depositEquipment());
        assertEquals(ActionResult.NOT_FOUND, bank.close());
        assertTrue(sent.isEmpty());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void anItemTheBankDoesNotShowIsNotFound() {
        openBank(new Slot(COINS, 100));
        assertEquals(ActionResult.NOT_FOUND, bank.withdraw(LOGS, 1));
        assertEquals(ActionResult.NOT_FOUND, bank.withdrawAll(LOGS));
        assertTrue(sent.isEmpty());
    }

    @Test
    void anAmountBelowOneIsRejected() {
        openBank(new Slot(COINS, 100));
        assertThrows(IllegalArgumentException.class, () -> bank.withdraw(COINS, 0));
        assertThrows(IllegalArgumentException.class, () -> bank.deposit(COINS, -1));
        assertTrue(sent.isEmpty());
    }

    @Test
    void depositsGoThroughTheBanksSidePanel() {
        openBank();
        openSidePanel();
        assertEquals(ActionResult.SENT, bank.deposit(COINS, 5));
        reference.menuAction(0, SIDE_ITEMS, MenuAction.CC_OP, 2, COINS);
        assertEquals(ActionResult.SENT, bank.depositAll(COINS));
        reference.menuAction(0, SIDE_ITEMS, MenuAction.CC_OP, ALL, COINS);
        assertEquals(expected, sent);
        assertEquals(ActionResult.NOT_FOUND, bank.deposit(LOGS, 1), "the inventory doesn't hold logs");
    }

    @Test
    void depositAllIsOnlyForTheWholeInventorysWorth() {
        openBank();
        openSidePanel();
        assertEquals(ActionResult.SENT, bank.deposit(COINS, 150), "the side panel's slot shows 100, the table 150");
        reference.menuAction(0, SIDE_ITEMS, MenuAction.CC_OP, ALL, COINS);
        assertEquals(expected, sent);

        sent.clear();
        expected.clear();
        assertEquals(ActionResult.SENT, bank.deposit(COINS, 120), "more than the slot, less than all held");
        reference.menuAction(0, SIDE_ITEMS, MenuAction.CC_OP, X, COINS);
        assertEquals(expected, sent);
    }

    @Test
    void depositXAnswersTheCountPrompt() {
        openBank();
        openSidePanel();
        assertEquals(ActionResult.SENT, bank.deposit(COINS, 7));
        reference.menuAction(0, SIDE_ITEMS, MenuAction.CC_OP, X, COINS);
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, 7));
        runTasks();
        reference.resumeCountDialog(7);
        assertEquals(expected, sent);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void theDepositButtonsRunTheirOps() {
        openBank();
        assertEquals(ActionResult.SENT, bank.depositInventory());
        reference.menuAction(-1, DEPOSIT_INVENTORY, MenuAction.CC_OP, 1, -1);
        assertEquals(ActionResult.SENT, bank.depositEquipment());
        reference.menuAction(-1, DEPOSIT_WORN, MenuAction.CC_OP, 1, -1);
        assertEquals(expected, sent);
    }

    @Test
    void aDepositButtonWithoutAnOpIsNoSuchOption() {
        openBank();
        game.setOps(depositWorn, "");
        assertEquals(ActionResult.NO_SUCH_OPTION, bank.depositEquipment());
        assertTrue(sent.isEmpty());
    }

    @Test
    void closeSendsTheCloseRow() {
        openBank();
        assertEquals(ActionResult.SENT, bank.close());
        reference.closeInterface();
        assertEquals(expected, sent);

        Client unconnected = new Client(game.memory, game.map);
        assertEquals(ActionResult.NOT_CONNECTED, unconnected.getBank().close());
        assertEquals(1, sent.size());
    }
}
