package com.kraken.client.plugins.antidrag;

import java.awt.event.KeyEvent;

/**
 * Whether Shift and Control count as held for Anti Drag, and what each going down or up does to the drag delay.
 * Shift matters only while the delay is Shift-only, and Control only while it lifts the delay and the delay isn't
 * Shift-only. Client thread only.
 */
final class AntiDragKeys {

    /**
     * What a key change asks of the drag delay.
     */
    enum Action {

        /**
         * Nothing changes.
         */
        NONE,

        /**
         * Apply the configured delay.
         */
        SET,

        /**
         * Put the game's default delay back.
         */
        RESET
    }

    private boolean shiftHeld;
    private boolean ctrlHeld;

    /**
     * A key went down.
     *
     * @param keyCode       its {@link KeyEvent} code
     * @param onShiftOnly   whether the delay is Shift-only
     * @param disableOnCtrl whether Control lifts the delay
     * @return what to do to the delay
     */
    Action pressed(int keyCode, boolean onShiftOnly, boolean disableOnCtrl) {
        if (keyCode == KeyEvent.VK_CONTROL && disableOnCtrl && !onShiftOnly) {
            ctrlHeld = true;
            return Action.RESET;
        }
        if (keyCode == KeyEvent.VK_SHIFT && onShiftOnly) {
            shiftHeld = true;
            return Action.SET;
        }
        return Action.NONE;
    }

    /**
     * A key went up.
     *
     * @param keyCode       its {@link KeyEvent} code
     * @param onShiftOnly   whether the delay is Shift-only
     * @param disableOnCtrl whether Control lifts the delay
     * @return what to do to the delay
     */
    Action released(int keyCode, boolean onShiftOnly, boolean disableOnCtrl) {
        if (keyCode == KeyEvent.VK_CONTROL && disableOnCtrl && !onShiftOnly) {
            ctrlHeld = false;
            return Action.SET;
        }
        if (keyCode == KeyEvent.VK_SHIFT && onShiftOnly) {
            shiftHeld = false;
            return Action.RESET;
        }
        return Action.NONE;
    }

    /**
     * Whether the configured delay is in force now, rather than the game's.
     *
     * @param onShiftOnly whether the delay is Shift-only
     * @return true while it applies
     */
    boolean isOverriding(boolean onShiftOnly) {
        return (!onShiftOnly || shiftHeld) && !ctrlHeld;
    }

    /**
     * Forgets a key the settings no longer listen to.
     *
     * @param onShiftOnly   whether the delay is Shift-only now
     * @param disableOnCtrl whether Control lifts it now
     */
    void settingsChanged(boolean onShiftOnly, boolean disableOnCtrl) {
        if (!disableOnCtrl) {
            ctrlHeld = false;
        }
        if (onShiftOnly) {
            shiftHeld = false;
        }
    }

    /**
     * Forgets both keys, as when the game loses the keyboard.
     */
    void clear() {
        shiftHeld = false;
        ctrlHeld = false;
    }
}
