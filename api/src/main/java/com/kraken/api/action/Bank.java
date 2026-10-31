package com.kraken.api.action;

import com.kraken.api.Client;

/**
 * The bank. Get it from {@code client.getBank()} or inject it.
 *
 * <p>Withdrawing and depositing run the bank's own ops by name ("Withdraw-10", "Deposit-All"), so they follow the
 * bank's quantity settings without reading them: the ops name their amounts ("Withdraw-14" once 14 was the last X).
 * An amount no op names runs Withdraw-X (or Deposit-X), and answers the count prompt once the server opens it.
 */
public final class Bank {

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
     * Whether the bank is open: its main interface ({@code Bankmain}) is loaded.
     *
     * @return true if it is open
     */
    public boolean isOpen() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether the bank holds an item.
     *
     * @param itemId the item's id
     * @return true if it does; false if the bank's contents can't be read (the bank hasn't been opened this session)
     */
    public boolean contains(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * How many of an item the bank holds.
     *
     * @param itemId the item's id
     * @return the quantity; 0 if none or the bank's contents can't be read
     */
    public int count(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Withdraws an amount of an item: "Withdraw-&lt;amount&gt;" when the item's bank widget offers it, else
     * "Withdraw-X" followed by the amount once the server opens the count prompt. The prompt is answered by a client
     * thread task, which gives up after a few ticks.
     *
     * @param itemId the item's id
     * @param amount how many, at least 1
     * @return {@link ActionResult#SENT} once the op is sent, or why nothing was sent: {@link ActionResult#NOT_FOUND} if
     *         the bank isn't open or doesn't show the item
     * @throws IllegalArgumentException if the amount is below 1
     */
    public ActionResult withdraw(int itemId, int amount) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Withdraws all of an item: "Withdraw-All".
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or doesn't show the item
     */
    public ActionResult withdrawAll(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Deposits an amount of an item from the bank's inventory panel, as {@link #withdraw} does with the "Deposit-"
     * ops.
     *
     * @param itemId the item's id
     * @param amount how many, at least 1
     * @return {@link ActionResult#SENT} once the op is sent, or why nothing was sent: {@link ActionResult#NOT_FOUND} if
     *         the bank isn't open or the inventory doesn't hold the item
     * @throws IllegalArgumentException if the amount is below 1
     */
    public ActionResult deposit(int itemId, int amount) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Deposits all of an item: "Deposit-All".
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if the bank isn't open
     *         or the inventory doesn't hold the item
     */
    public ActionResult depositAll(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Deposits the whole inventory with the bank's button for it.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the bank isn't open
     */
    public ActionResult depositInventory() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Deposits every worn item with the bank's button for it.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if the bank isn't open
     */
    public ActionResult depositEquipment() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Closes the bank, as its close button does ({@link Actions#closeInterface()}).
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent
     */
    public ActionResult close() {
        throw new UnsupportedOperationException("not built yet");
    }
}
