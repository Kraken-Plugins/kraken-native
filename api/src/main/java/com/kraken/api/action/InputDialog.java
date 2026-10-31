package com.kraken.api.action;

import com.kraken.api.gameval.VarClientID;

import java.util.Arrays;

/**
 * The chatbox dialogs the server asks the player to answer, each answered with one of the resume packets, and the
 * chatbox input modes the client's scripts keep in varc {@link VarClientID#MESLAYERMODE} while each one is showing.
 * Find the dialog showing now with {@code InputDialog.of(client.getVarcIntValue(VarClientID.MESLAYERMODE))}.
 *
 * <p>The modes come from the client's scripts (241.3's cache): every chatbox input opens through one proc (script 677)
 * that stores its mode argument in the varc, and the chatbox's key handler (script 681) sends the answer for the mode
 * when Enter is pressed. The other modes, such as 0 (no input showing), the friend list's inputs and the bank search,
 * send no resume packet.
 */
public enum InputDialog {
    /**
     * A number ("Enter amount:"), answered by {@link Actions#resumeCountDialog}. The script the server runs for it
     * (108) opens mode 7; script 4480 opens 19.
     */
    COUNT(7, 19),

    /**
     * A player's name, answered by {@link Actions#resumeNameDialog}. Script 109 opens mode 8.
     */
    NAME(8),

    /**
     * A line of text, answered by {@link Actions#resumeStringDialog}. Script 110 opens mode 9, or one of the others
     * here for the variants its int argument picks.
     */
    STRING(9, 15, 20, 21, 27, 29),

    /**
     * An item search ("What would you like to buy?"), answered by {@link Actions#resumeObjDialog}. Picking a result
     * sends the answer rather than Enter. The script the server runs for it (750) opens mode 14; script 3558, which an
     * interface opens itself, opens 17.
     */
    OBJ(14, 17);

    private final int[] modes;

    InputDialog(int... modes) {
        this.modes = modes;
    }

    /**
     * The dialog a chatbox input mode belongs to.
     *
     * @param mode the value of varc {@link VarClientID#MESLAYERMODE}
     * @return the dialog the mode answers, or null if the mode sends no resume packet (0: no input showing)
     */
    public static InputDialog of(int mode) {
        for (InputDialog dialog : values()) {
            if (dialog.hasMode(mode)) {
                return dialog;
            }
        }
        return null;
    }

    /**
     * The chatbox input modes the client's scripts set while this dialog is showing.
     *
     * @return a copy of the modes, the one the plain dialog opens first
     */
    public int[] getModes() {
        return modes.clone();
    }

    /**
     * Whether a chatbox input mode is one of this dialog's.
     *
     * @param mode the value of varc {@link VarClientID#MESLAYERMODE}
     * @return true if the client's scripts set this mode while this dialog is showing
     */
    public boolean hasMode(int mode) {
        return Arrays.stream(modes).anyMatch(m -> m == mode);
    }
}
