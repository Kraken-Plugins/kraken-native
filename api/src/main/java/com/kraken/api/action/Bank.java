package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.gameval.VarClientID;
import com.kraken.api.internal.action.Interactions;
import com.kraken.api.internal.action.OptionMatcher;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.widgets.Widget;

import java.util.logging.Logger;

/**
 * The bank. Get it from {@code client.getBank()} or inject it.
 *
 * <p>Withdrawing and depositing run the bank's own ops by name ("Withdraw-10", "Deposit-All"), so they follow the
 * bank's quantity settings without reading them: the ops name their amounts ("Withdraw-14" once 14 was the last X).
 * An amount no op names runs Withdraw-X (or Deposit-X), and answers the count prompt once the server opens it.
 */
public final class Bank {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * How many game ticks a Withdraw-X or Deposit-X waits for its count prompt before giving up.
     */
    private static final int COUNT_PROMPT_TICKS = 5;

    private static final String WITHDRAW = "Withdraw";
    private static final String DEPOSIT = "Deposit";

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Bank(Client client) {
        this.client = client;
    }

    /**
     * Whether the bank is open: its main interface ({@code Bankmain}) is on screen. A group the client has loaded but
     * closed doesn't count.
     *
     * @return true if it is open
     */
    public boolean isOpen() {
        Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return root != null && !root.isHidden();
    }

    /**
     * Whether the bank holds an item.
     *
     * @param itemId the item's id
     * @return true if it does; false if the bank's contents can't be read (the bank hasn't been opened this session)
     */
    public boolean contains(int itemId) {
        ItemContainer items = bankItems();
        return items != null && items.contains(itemId);
    }

    /**
     * How many of an item the bank holds.
     *
     * @param itemId the item's id
     * @return the quantity; 0 if none or the bank's contents can't be read
     */
    public int count(int itemId) {
        ItemContainer items = bankItems();
        return items == null ? 0 : items.count(itemId);
    }

    /**
     * Withdraws an amount of an item: "Withdraw-&lt;amount&gt;" when the item's bank widget offers it, else
     * "Withdraw-All" when the amount covers the whole stack, else "Withdraw-X" followed by the amount once the server
     * opens the count prompt. The prompt is answered by a client thread task, which gives up after a few ticks. A
     * prompt already on screen when the op is sent is left alone; only one that opens after it is answered.
     *
     * @param itemId the item's id
     * @param amount how many, at least 1
     * @return {@link ActionResult#SENT} once the op is sent, or why nothing was sent: {@link ActionResult#NOT_FOUND} if
     *         the bank isn't open or no slot shows a quantity of the item (placeholders don't count);
     *         {@link ActionResult#NO_SUCH_OPTION} if the slot offers none of the withdraw ops
     * @throws IllegalArgumentException if the amount is below 1
     */
    public ActionResult withdraw(int itemId, int amount) {
        requireAmount(amount);
        Widget slot = shownSlot(itemId);
        return slot == null ? notFound() : amountOp(slot, WITHDRAW, amount, slot.getItemQuantity());
    }

    /**
     * Withdraws all of an item: "Withdraw-All".
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or doesn't show the item, {@link ActionResult#NO_SUCH_OPTION} if the slot has no Withdraw-All
     */
    public ActionResult withdrawAll(int itemId) {
        Widget slot = shownSlot(itemId);
        return slot == null ? notFound() : slot.interact(WITHDRAW + "-All");
    }

    /**
     * Deposits an amount of an item from the bank's inventory panel, as {@link #withdraw} does with the "Deposit-"
     * ops.
     *
     * @param itemId the item's id
     * @param amount how many, at least 1
     * @return {@link ActionResult#SENT} once the op is sent, or why nothing was sent: {@link ActionResult#NOT_FOUND} if
     *         the bank isn't open or the inventory doesn't hold the item; {@link ActionResult#NO_SUCH_OPTION} if the
     *         slot offers none of the deposit ops
     * @throws IllegalArgumentException if the amount is below 1
     */
    public ActionResult deposit(int itemId, int amount) {
        requireAmount(amount);
        Widget slot = depositSlot(itemId);
        return slot == null ? notFound()
                : amountOp(slot, DEPOSIT, amount, client.getInventory().count(itemId));
    }

    /**
     * Deposits all of an item: "Deposit-All".
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or the inventory doesn't hold the item, {@link ActionResult#NO_SUCH_OPTION} if the slot has no
     *         Deposit-All
     */
    public ActionResult depositAll(int itemId) {
        Widget slot = depositSlot(itemId);
        return slot == null ? notFound() : slot.interact(DEPOSIT + "-All");
    }

    /**
     * Deposits the whole inventory with the bank's "Deposit inventory" button.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or the button isn't shown, {@link ActionResult#NO_SUCH_OPTION} if the button has no op
     */
    public ActionResult depositInventory() {
        return bankButton(InterfaceID.Bankmain.DEPOSITINV);
    }

    /**
     * Deposits every worn item with the bank's "Deposit worn items" button.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or the button isn't shown, {@link ActionResult#NO_SUCH_OPTION} if the button has no op
     */
    public ActionResult depositEquipment() {
        return bankButton(InterfaceID.Bankmain.DEPOSITWORN);
    }

    /**
     * Closes the bank, as its close button does ({@link Actions#closeInterface()}). Nothing is sent while the bank
     * isn't open, so the close can't reach another interface.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the bank isn't
     *         open, {@link ActionResult#NOT_CONNECTED} if the hook isn't connected, {@link ActionResult#UNSUPPORTED} if
     *         this build's mapping lacks the close row
     */
    public ActionResult close() {
        if (!isOpen()) {
            return notFound();
        }
        Actions actions = client.getActions();
        if (!actions.isSupported(MenuAction.WIDGET_CLOSE)) {
            return ActionResult.UNSUPPORTED;
        }
        if (actions.closeInterface()) {
            return ActionResult.SENT;
        }
        return actions.isAvailable() ? ActionResult.UNSUPPORTED : ActionResult.NOT_CONNECTED;
    }

    private ItemContainer bankItems() {
        return client.getItemContainer(InventoryID.BANK);
    }

    /**
     * The bank slot to withdraw from: the first placeholder-free slot of the item, the bank open.
     *
     * @param itemId the item's id
     * @return the slot's widget, or null if the bank isn't open or no slot shows a quantity of the item
     */
    private Widget shownSlot(int itemId) {
        if (!isOpen()) {
            log.fine(() -> "the bank isn't open; item " + itemId + " isn't in it");
            return null;
        }
        Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (items != null) {
            for (Widget slot : items.getDynamicChildren()) {
                if (slot.getItemId() == itemId && slot.getItemQuantity() > 0) {
                    return slot;
                }
            }
        }
        log.fine(() -> "the bank shows no slot of item " + itemId + " with a quantity");
        return null;
    }

    /**
     * The inventory slot to deposit from, the bank open and the inventory panel holding the item.
     *
     * @param itemId the item's id
     * @return the slot's widget, or null if the bank isn't open or no inventory panel shows the item
     */
    private Widget depositSlot(int itemId) {
        if (!isOpen()) {
            log.fine(() -> "the bank isn't open; item " + itemId + " isn't deposited");
            return null;
        }
        return client.getInventory().getSlot(itemId);
    }

    /**
     * Runs the op of a withdraw or deposit for an amount: "Verb-amount" when the slot offers it, "Verb-All" when the
     * amount covers all there is and the slot offers that, else "Verb-X". A Verb-X sent here also queues the answer
     * to its count prompt.
     *
     * @param slot   the bank or inventory slot
     * @param verb   "Withdraw" or "Deposit"
     * @param amount how many, at least 1
     * @param held   how many there are to move: the bank's stack, or every inventory slot's together, since All moves
     *               them all
     * @return the result of the op sent
     */
    private ActionResult amountOp(Widget slot, String verb, int amount, int held) {
        String[] ops = client.getInteractions().actions(slot);
        String exact = verb + "-" + amount;
        if (offers(ops, exact)) {
            return slot.interact(exact);
        }
        if (amount >= held && offers(ops, verb + "-All")) {
            return slot.interact(verb + "-All");
        }
        boolean promptOpen = isCountPrompt();
        ActionResult result = slot.interact(verb + "-X");
        if (result.isSent()) {
            answerCountPrompt(amount, promptOpen);
        }
        return result;
    }

    /**
     * Queues the task that answers the count prompt a Verb-X opens. If a prompt was already open when the op was sent,
     * the task waits for it to close first, so only the prompt the op opens gets the amount. It returns true once it
     * answers, or after {@link #COUNT_PROMPT_TICKS} game ticks without a prompt.
     *
     * @param amount     the number to answer with
     * @param promptOpen whether a count prompt was open when the op was sent
     */
    private void answerCountPrompt(int amount, boolean promptOpen) {
        int deadline = client.getTickCount() + COUNT_PROMPT_TICKS;
        boolean[] waitingForClose = {promptOpen};
        client.queueTask(() -> {
            boolean open = isCountPrompt();
            if (waitingForClose[0]) {
                waitingForClose[0] = open;
            } else if (open) {
                if (!client.getActions().resumeCountDialog(amount)) {
                    log.fine(() -> "the count prompt for " + amount + " wasn't answered: dialogs or hook unavailable");
                }
                return true;
            }
            if (client.getTickCount() >= deadline) {
                log.fine(() -> "no count prompt opened for " + amount + " within " + COUNT_PROMPT_TICKS + " ticks");
                return true;
            }
            return false;
        });
    }

    /**
     * Runs one of the bank's buttons ({@code Bankmain} deposit components) with its first op.
     *
     * @param componentId the packed id of the button
     * @return the result of the click
     */
    private ActionResult bankButton(int componentId) {
        if (!isOpen()) {
            return notFound();
        }
        Widget button = client.getWidget(componentId);
        if (button == null || button.isHidden()) {
            log.fine(() -> "the bank button " + componentId + " isn't shown");
            return ActionResult.NOT_FOUND;
        }
        return button.click();
    }

    private boolean isCountPrompt() {
        return InputDialog.of(client.getVarcIntValue(VarClientID.MESLAYERMODE)) == InputDialog.COUNT;
    }

    private static boolean offers(String[] ops, String option) {
        return OptionMatcher.match(ops, option, Interactions.COMPONENT_OPS) > 0;
    }

    private static void requireAmount(int amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be at least 1, was " + amount);
        }
    }

    private static ActionResult notFound() {
        return ActionResult.NOT_FOUND;
    }
}
