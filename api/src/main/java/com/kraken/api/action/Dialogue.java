package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarClientID;
import com.kraken.api.internal.action.ActionRequest;
import com.kraken.api.internal.action.ClickPoints;
import com.kraken.api.internal.action.OptionMatcher;
import com.kraken.api.widgets.Widget;
import com.kraken.api.widgets.WidgetConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * The chatbox dialogues: "Click here to continue", the options menu and the input prompts. Get it from
 * {@code client.getDialogue()} or inject it.
 *
 * <p>It looks for pause buttons and the options menu ({@code Chatmenu.OPTIONS}) rather than listing every dialogue
 * interface, so new dialogue layouts work without changes. The input prompts are told apart by varc
 * {@code MESLAYERMODE} ({@link InputDialog}).
 */
public final class Dialogue {

    private static final Logger log = Logger.getLogger("kraken.api");

    /**
     * The group of the options menu, which its pause buttons don't count as a continue button in.
     */
    private static final int CHATMENU = InterfaceID.Chatmenu.OPTIONS >>> 16;

    /**
     * The old-style button type of a pause ("Click here to continue") button.
     */
    private static final int PAUSE_BUTTON_TYPE = 6;

    /**
     * The most characters a text answer can hold: the client's size byte counts them plus the terminator.
     */
    private static final int MAX_TEXT = 254;

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
        return canContinue() || !optionRows().isEmpty() || getInputDialog() != null;
    }

    /**
     * Whether a pause button ("Click here to continue") is showing outside the options menu.
     *
     * @return true if one is
     */
    public boolean canContinue() {
        return findContinue() != null;
    }

    /**
     * Presses the pause button ("Click here to continue") showing. Named so because {@code continue} is a Java
     * keyword.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if none is showing
     */
    public ActionResult clickContinue() {
        Widget pause = findContinue();
        if (pause == null) {
            log.fine("no continue button is showing");
            return ActionResult.NOT_FOUND;
        }
        return pressPause(pause, pause.getIndex(), pause.getId(), "the continue button " + pause);
    }

    /**
     * The options of the options menu showing, as shown, without markup or the title row.
     *
     * @return the options, the first shown first; empty if no options menu is showing
     */
    public List<String> getOptions() {
        return optionRows().stream().map(row -> OptionMatcher.plain(row.getText())).toList();
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
        Objects.requireNonNull(option, "option");
        List<Widget> rows = showingRows();
        if (rows == null) {
            return ActionResult.NOT_FOUND;
        }
        String[] texts = rows.stream().map(row -> OptionMatcher.plain(row.getText())).toArray(String[]::new);
        int op = OptionMatcher.match(texts, option, texts.length);
        if (op < 0) {
            return noOption("\"" + option + "\"", texts);
        }
        return pressOption(rows.get(op - 1));
    }

    /**
     * Chooses an option of the options menu by its number, as shown.
     *
     * @param option the option, 1 for the first shown
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#NOT_FOUND} if no options menu
     *         is showing, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     */
    public ActionResult selectOption(int option) {
        List<Widget> rows = showingRows();
        if (rows == null) {
            return ActionResult.NOT_FOUND;
        }
        if (option < 1 || option > rows.size()) {
            String[] texts = rows.stream().map(row -> OptionMatcher.plain(row.getText())).toArray(String[]::new);
            return noOption(String.valueOf(option), texts);
        }
        return pressOption(rows.get(option - 1));
    }

    /**
     * The input prompt showing.
     *
     * @return the prompt, by varc {@code MESLAYERMODE}; null if none that a resume packet answers is showing
     */
    public InputDialog getInputDialog() {
        return InputDialog.of(client.getVarcIntValue(VarClientID.MESLAYERMODE));
    }

    /**
     * Answers an "Enter amount" prompt, after checking it is the one showing.
     *
     * @param amount the amount
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no count prompt is
     *         showing
     */
    public ActionResult enterAmount(int amount) {
        return answer(InputDialog.COUNT, () -> client.getActions().resumeCountDialog(amount), "amount " + amount);
    }

    /**
     * Answers a name prompt, after checking it is the one showing.
     *
     * @param name the name
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no name prompt is
     *         showing
     * @throws NullPointerException     if the name is null
     * @throws IllegalArgumentException if the name is over 254 characters, or has characters outside the basic plane
     */
    public ActionResult enterName(String name) {
        checkText(name);
        return answer(InputDialog.NAME, () -> client.getActions().resumeNameDialog(name), "name " + name);
    }

    /**
     * Answers a text prompt, after checking it is the one showing.
     *
     * @param text the text
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no text prompt is
     *         showing
     * @throws NullPointerException     if the text is null
     * @throws IllegalArgumentException if the text is over 254 characters, or has characters outside the basic plane
     */
    public ActionResult enterText(String text) {
        checkText(text);
        return answer(InputDialog.STRING, () -> client.getActions().resumeStringDialog(text), "text " + text);
    }

    /**
     * Answers an item search prompt with an item, after checking it is the one showing.
     *
     * @param itemId the item's id
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NOT_FOUND} if no item prompt is
     *         showing
     */
    public ActionResult selectItem(int itemId) {
        return answer(InputDialog.OBJ, () -> client.getActions().resumeObjDialog(itemId), "item " + itemId);
    }

    /**
     * The first pause button showing outside the options menu: a component of old-style type 6, or one whose click
     * mask has the pause bit. Hidden components and those of the options menu's group are skipped.
     *
     * @return the component, or null if none is showing
     */
    private Widget findContinue() {
        for (int group : client.getOpenWidgetGroups()) {
            if (group == CHATMENU) {
                continue;
            }
            Widget[] statics = client.getWidgetGroup(group);
            if (statics == null) {
                continue;
            }
            for (Widget widget : statics) {
                if (widget == null) {
                    continue;
                }
                if (isShowingPause(widget)) {
                    return widget;
                }
                for (Widget child : widget.getDynamicChildren()) {
                    if (isShowingPause(child)) {
                        return child;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The rows of the options menu when it is showing.
     *
     * @return the rows, or null if no options menu is showing
     */
    private List<Widget> showingRows() {
        Widget menu = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        return menu == null || menu.isHidden() ? null : optionRows();
    }

    /**
     * The option rows of the options menu: its dynamic children that are shown, pause buttons and have text, in
     * child order. The title row has no pause bit, so it is left out.
     *
     * @return the rows; empty if no options menu is showing
     */
    private List<Widget> optionRows() {
        Widget menu = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (menu == null || menu.isHidden()) {
            return List.of();
        }
        return Arrays.stream(menu.getDynamicChildren())
                .filter(row -> isShowingPause(row) && OptionMatcher.isNamed(row.getText()))
                .toList();
    }

    /**
     * Presses an option row's pause button. The row's slot is its child index.
     *
     * @param row the option row
     * @return the result
     */
    private ActionResult pressOption(Widget row) {
        return pressPause(row, row.getIndex(), InterfaceID.Chatmenu.OPTIONS,
                "the option " + OptionMatcher.plain(row.getText()));
    }

    /**
     * Presses a pause button: the continue row of the client's menu, with the click the interaction policy asks for.
     *
     * @param widget the widget the click goes to
     * @param slot   the component's slot, -1 for a static component
     * @param packed the packed id of the component
     * @param what   the button, for the log
     * @return the result
     */
    private ActionResult pressPause(Widget widget, int slot, int packed, String what) {
        ActionRequest request = new ActionRequest(MenuAction.WIDGET_CONTINUE, slot, packed, 0, -1, 0, null, null);
        return client.getInteractions().run(request, () -> ClickPoints.widget(client, widget), what);
    }

    /**
     * Answers an input prompt if it is the one showing and the resume packet can be sent.
     *
     * @param dialog the prompt the answer is for
     * @param resume sends the answer, true if it went
     * @param what   the answer, for the log
     * @return {@link ActionResult#SENT}; {@link ActionResult#NOT_FOUND} if the prompt isn't showing;
     *         {@link ActionResult#UNSUPPORTED} if this build's mapping lacks the dialog packets;
     *         {@link ActionResult#NOT_CONNECTED} if the hook isn't connected or the packet couldn't be sent
     */
    private ActionResult answer(InputDialog dialog, BooleanSupplier resume, String what) {
        if (getInputDialog() != dialog) {
            log.fine(() -> dialog + " prompt isn't showing, so " + what + " isn't entered");
            return ActionResult.NOT_FOUND;
        }
        if (resume.getAsBoolean()) {
            return ActionResult.SENT;
        }
        ActionResult result = !client.getActions().isDialogSupported() ? ActionResult.UNSUPPORTED
                : ActionResult.NOT_CONNECTED;
        log.fine(() -> "could not enter " + what + ": " + result);
        return result;
    }

    /**
     * Checks a text answer can be sent: at most 254 characters, all in the basic plane.
     *
     * @param text the text
     * @throws NullPointerException     if the text is null
     * @throws IllegalArgumentException if it is too long or has characters outside the basic plane
     */
    private static void checkText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new IllegalArgumentException("a text answer holds at most " + MAX_TEXT + " characters");
        }
        if (text.codePoints().anyMatch(codePoint -> codePoint > 0xffff)) {
            throw new IllegalArgumentException("a text answer can only hold characters of the basic plane");
        }
    }

    /**
     * Logs that the options menu has no such option, and returns the result.
     *
     * @param wanted the option asked for
     * @param texts  the options showing, in order
     * @return {@link ActionResult#NO_SUCH_OPTION}
     */
    private static ActionResult noOption(String wanted, String[] texts) {
        log.fine(() -> "the options menu has no option " + wanted + "; it has " + OptionMatcher.describe(texts));
        return ActionResult.NO_SUCH_OPTION;
    }

    /**
     * Whether a component is shown and is a pause button: old-style type 6, or its click mask has the pause bit.
     *
     * @param widget the component
     * @return true if it is a pause button that is shown
     */
    private static boolean isShowingPause(Widget widget) {
        if (widget.isHidden()) {
            return false;
        }
        int mask = widget.getClickMask();
        return widget.getEvent() == PAUSE_BUTTON_TYPE || mask != -1 && (mask & WidgetConfig.PAUSE_BUTTON) != 0;
    }
}
