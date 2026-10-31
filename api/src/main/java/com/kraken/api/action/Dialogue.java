package com.kraken.api.action;

import com.kraken.api.Client;

import java.util.List;

/**
 * The chatbox dialogues: "Click here to continue", the options menu and the input prompts. Get it from
 * {@code client.getDialogue()} or inject it.
 *
 * <p>It looks for pause buttons and the options menu ({@code Chatmenu.OPTIONS}) rather than listing every dialogue
 * interface, so new dialogue layouts work without changes. The input prompts are told apart by varc
 * {@code MESLAYERMODE} ({@link InputDialog}).
 */
public final class Dialogue {

    private final Client client;

    /**
     * Creates the service. {@code Client} makes the one plugins use.
     *
     * @param client the client
     */
    public Dialogue(Client client) {
        this.client = client;
    }

    /**
     * Whether a dialogue is open: a continue button, an options menu or an input prompt is showing.
     *
     * @return true if one is
     */
    public boolean isOpen() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Whether a pause button ("Click here to continue") is showing outside the options menu.
     *
     * @return true if one is
     */
    public boolean canContinue() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Presses the pause button ("Click here to continue") showing. Named so because {@code continue} is a Java
     * keyword.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if none is showing
     */
    public ActionResult clickContinue() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The options of the options menu showing, as shown, without markup or the title row.
     *
     * @return the options, the first shown first; empty if no options menu is showing
     */
    public List<String> getOptions() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Chooses an option of the options menu by its text, matched loosely (markup and case ignored; spaces, {@code -}
     * and {@code _} count as the same).
     *
     * @param option the option's text
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if no options menu
     *         is showing, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     * @throws NullPointerException if the option is null
     */
    public ActionResult selectOption(String option) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Chooses an option of the options menu by its number, as shown.
     *
     * @param option the option, 1 for the first shown
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if no options menu
     *         is showing, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     */
    public ActionResult selectOption(int option) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * The input prompt showing.
     *
     * @return the prompt, by varc {@code MESLAYERMODE}; null if none that a resume packet answers is showing
     */
    public InputDialog getInputDialog() {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Answers an "Enter amount" prompt, after checking it is the one showing.
     *
     * @param amount the amount
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no count prompt is
     *         showing
     */
    public ActionResult enterAmount(int amount) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Answers a name prompt, after checking it is the one showing.
     *
     * @param name the name
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no name prompt is
     *         showing
     */
    public ActionResult enterName(String name) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Answers a text prompt, after checking it is the one showing.
     *
     * @param text the text
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no text prompt is
     *         showing
     */
    public ActionResult enterText(String text) {
        throw new UnsupportedOperationException("not built yet");
    }

    /**
     * Answers an item search prompt with an item, after checking it is the one showing.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no item prompt is
     *         showing
     */
    public ActionResult selectItem(int itemId) {
        throw new UnsupportedOperationException("not built yet");
    }
}
