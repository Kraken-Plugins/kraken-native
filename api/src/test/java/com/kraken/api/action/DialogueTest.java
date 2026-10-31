package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarClientID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.widgets.WidgetConfig;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Dialogue}: the continue buttons outside the options menu, the options menu's rows, and the input prompts
 * answered only while their chatbox mode shows.
 */
class DialogueTest {

    private static final int OPTIONS = InterfaceID.Chatmenu.OPTIONS;
    private static final int CHATMENU = OPTIONS >>> 16;
    private static final int CHAT_ALL = 162 << 16 | 1;
    private static final int BUTTONS = 165;
    private static final int PAUSES = 166;
    private static final int COINS = 995;

    private GameFixture game;
    private Client client;
    private Dialogue dialogue;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        dialogue = client.getDialogue();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
        setServerEvents();
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(0), "the batch the matching Actions call sends");
    }

    private void setMode(int mode) {
        game.setVarcs(Map.of(VarClientID.MESLAYERMODE, mode));
    }

    /**
     * Sets the server's click masks, after the chatbox's All tab's own mask is replaced by its tab bit alone (the
     * fixture gives the tab a pause bit, which would make the chatbox a continue button in every test).
     *
     * @param ranges each {@code {component, first slot, last slot, mask}}
     */
    private void setServerEvents(int[]... ranges) {
        int[][] all = new int[ranges.length + 1][];
        all[0] = new int[]{CHAT_ALL, -1, -1, WidgetConfig.transmitAction(0)};
        System.arraycopy(ranges, 0, all, 1, ranges.length);
        game.setServerEvents(all);
    }

    /**
     * Opens a group on screen with one static layer, whose dynamic children are text rows with the given texts.
     *
     * @param group the group
     * @param texts the rows' texts, in child order
     */
    private void openRows(int group, String... texts) {
        long root = game.addComponent(group << 16, -1, -1, -1, WidgetType.LAYER, 0, 0, 519, 165);
        long layer = game.addComponent(group << 16 | 1, -1, -1, group << 16, WidgetType.LAYER, 0, 0, 519, 165);
        game.setGroup(group, root, layer);
        long[] rows = new long[texts.length];
        int packed = group << 16 | 1;
        for (int i = 0; i < texts.length; i++) {
            rows[i] = game.addComponent(packed, i, -1, packed, WidgetType.TEXT, 0, 14 * i, 400, 14);
            game.memory.putSsoString(rows[i] + game.map.offset("IfType.text"), texts[i]);
        }
        game.setDynamicChildren(layer, rows);
        game.openGroup(group, 0, 0);
    }

    /**
     * Opens the options menu with a title row and the options, the options' rows marked as pause buttons.
     *
     * @param title   the title row's text
     * @param options the options' texts, in order
     */
    private void openOptionsMenu(String title, String... options) {
        String[] texts = new String[options.length + 1];
        texts[0] = title;
        System.arraycopy(options, 0, texts, 1, options.length);
        openRows(CHATMENU, texts);
        setServerEvents(new int[]{OPTIONS, 1, options.length, WidgetConfig.PAUSE_BUTTON});
    }

    /**
     * Puts a static button at child 1 of a group, under an empty root, and opens the group.
     *
     * @param group  the group
     * @param packed the button's packed id, {@code group << 16 | 1}
     * @return the button's address
     */
    private long openButton(int group, int packed) {
        long root = game.addComponent(group << 16, -1, -1, -1, WidgetType.LAYER, 0, 0, 519, 165);
        long button = game.addComponent(packed, -1, -1, group << 16, WidgetType.TEXT, 0, 0, 100, 14);
        game.setGroup(group, root, button);
        game.openGroup(group, 0, 0);
        return button;
    }

    @Test
    void nothingShowsByDefault() {
        assertFalse(dialogue.isOpen());
        assertFalse(dialogue.canContinue());
        assertEquals(ActionResult.NOT_FOUND, dialogue.clickContinue());
        assertTrue(dialogue.getOptions().isEmpty());
        assertEquals(ActionResult.NOT_FOUND, dialogue.selectOption("Yes"));
        assertNull(dialogue.getInputDialog());
        assertTrue(sent.isEmpty());
    }

    @Test
    void aContinueButtonOfTypeSixIsFound() {
        long button = openButton(BUTTONS, BUTTONS << 16 | 1);
        game.memory.putInt(button + game.map.offset("IfType.event"), 6);
        assertTrue(dialogue.canContinue());
        assertTrue(dialogue.isOpen());
        assertEquals(ActionResult.SENT, dialogue.clickContinue());
        reference.menuAction(-1, BUTTONS << 16 | 1, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();
    }

    @Test
    void aContinueButtonWithThePauseBitIsFoundWithItsSlot() {
        openButton(PAUSES, PAUSES << 16 | 1);
        setServerEvents(new int[]{PAUSES << 16 | 1, -1, -1, WidgetConfig.PAUSE_BUTTON});
        assertTrue(dialogue.canContinue());
        assertEquals(ActionResult.SENT, dialogue.clickContinue());
        reference.menuAction(-1, PAUSES << 16 | 1, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();
    }

    @Test
    void aHiddenOrNonPauseWidgetIsNoContinueButton() {
        long button = openButton(BUTTONS, BUTTONS << 16 | 1);
        game.memory.putInt(button + game.map.offset("IfType.event"), 4);
        assertFalse(dialogue.canContinue(), "a type 4 button isn't a pause button");

        game.memory.putInt(button + game.map.offset("IfType.event"), 6);
        game.memory.putByte(button + game.map.offset("IfType.hidden"), 1);
        assertFalse(dialogue.canContinue(), "a hidden pause button");
        assertEquals(ActionResult.NOT_FOUND, dialogue.clickContinue());

        game.memory.putByte(button + game.map.offset("IfType.hidden"), 0);
        game.closeGroup(BUTTONS);
        assertFalse(dialogue.canContinue(), "its group closed");
        assertTrue(sent.isEmpty());
    }

    @Test
    void theOptionsMenuIsNoContinueButton() {
        openOptionsMenu("Pick one", "Yes", "No");
        assertFalse(dialogue.canContinue(), "the option rows have the pause bit, but they are options");
        assertEquals(ActionResult.NOT_FOUND, dialogue.clickContinue());
        assertTrue(dialogue.isOpen(), "the options menu is open");
    }

    @Test
    void optionsAreReadInOrderWithoutTheTitle() {
        openOptionsMenu("Pick one", "Chop-down", "<col=ff>Leave</col>", "Maybe");
        assertEquals(List.of("Chop-down", "Leave", "Maybe"), dialogue.getOptions());
        assertTrue(dialogue.isOpen());
    }

    @Test
    void anOptionIsChosenByItsTextLoosely() {
        openOptionsMenu("Pick one", "Chop-down", "<col=ff>Leave</col>", "Maybe");
        assertEquals(ActionResult.SENT, dialogue.selectOption("chop down"));
        reference.menuAction(1, OPTIONS, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, dialogue.selectOption("  LEAVE "));
        reference.menuAction(2, OPTIONS, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();
    }

    @Test
    void anOptionIsChosenByItsNumberFromOne() {
        openOptionsMenu("Pick one", "Chop-down", "Leave", "Maybe");
        assertEquals(ActionResult.SENT, dialogue.selectOption(3));
        reference.menuAction(3, OPTIONS, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.SENT, dialogue.selectOption(1));
        reference.menuAction(1, OPTIONS, MenuAction.WIDGET_CONTINUE, 0);
        assertSentAsReference();
    }

    @Test
    void theTitleAndMissingOptionsAreNoSuchOption() {
        openOptionsMenu("Pick one", "Leave", "Maybe");
        assertEquals(ActionResult.NO_SUCH_OPTION, dialogue.selectOption("Pick one"), "the title isn't an option");
        assertEquals(ActionResult.NO_SUCH_OPTION, dialogue.selectOption("Attack"));
        assertEquals(ActionResult.NO_SUCH_OPTION, dialogue.selectOption(0));
        assertEquals(ActionResult.NO_SUCH_OPTION, dialogue.selectOption(3));
        assertTrue(sent.isEmpty());
    }

    @Test
    void noOptionsMenuIsNotFound() {
        openOptionsMenu("Pick one", "Leave");
        game.closeGroup(CHATMENU);
        assertTrue(dialogue.getOptions().isEmpty());
        assertFalse(dialogue.isOpen());
        assertEquals(ActionResult.NOT_FOUND, dialogue.selectOption("Leave"));
        assertEquals(ActionResult.NOT_FOUND, dialogue.selectOption(1));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theCountPromptIsAnsweredOnlyWhileItShows() {
        setMode(7);
        assertEquals(InputDialog.COUNT, dialogue.getInputDialog());
        assertTrue(dialogue.isOpen());
        assertEquals(ActionResult.NOT_FOUND, dialogue.enterName("Zezima"));
        assertEquals(ActionResult.NOT_FOUND, dialogue.enterText("hi"));
        assertEquals(ActionResult.NOT_FOUND, dialogue.selectItem(COINS));
        assertTrue(sent.isEmpty());

        assertEquals(ActionResult.SENT, dialogue.enterAmount(5));
        reference.resumeCountDialog(5);
        assertSentAsReference();
    }

    @Test
    void theNameTextAndItemPromptsAreAnsweredInTheirModes() {
        setMode(8);
        assertEquals(InputDialog.NAME, dialogue.getInputDialog());
        assertEquals(ActionResult.SENT, dialogue.enterName("Zezima"));
        reference.resumeNameDialog("Zezima");
        assertSentAsReference();

        sent.clear();
        setMode(9);
        assertEquals(InputDialog.STRING, dialogue.getInputDialog());
        assertEquals(ActionResult.SENT, dialogue.enterText("hello"));
        reference.resumeStringDialog("hello");
        assertSentAsReference();

        sent.clear();
        setMode(14);
        assertEquals(InputDialog.OBJ, dialogue.getInputDialog());
        assertEquals(ActionResult.SENT, dialogue.selectItem(COINS));
        reference.resumeObjDialog(COINS);
        assertSentAsReference();
    }

    @Test
    void noPromptShowsWithModeZero() {
        setMode(0);
        assertNull(dialogue.getInputDialog());
        assertFalse(dialogue.isOpen());
        assertEquals(ActionResult.NOT_FOUND, dialogue.enterAmount(5));
        assertTrue(sent.isEmpty());
    }

    @Test
    void textAnswersOverTheLimitAreRefused() {
        setMode(9);
        assertThrows(IllegalArgumentException.class, () -> dialogue.enterText("a".repeat(255)));
        assertThrows(IllegalArgumentException.class,
                () -> dialogue.enterText(new String(Character.toChars(0x1f600))));
        assertThrows(NullPointerException.class, () -> dialogue.enterName(null));
        assertTrue(sent.isEmpty());

        assertEquals(ActionResult.SENT, dialogue.enterText("a".repeat(254)));
        reference.resumeStringDialog("a".repeat(254));
        assertSentAsReference();
    }

    @Test
    void withoutTheHookNothingIsSent() {
        setMode(7);
        client.getActions().setInvoker(null);
        assertEquals(ActionResult.NOT_CONNECTED, dialogue.enterAmount(5));
        openOptionsMenu("Pick one", "Leave");
        assertEquals(ActionResult.NOT_CONNECTED, dialogue.selectOption("Leave"));
    }
}
