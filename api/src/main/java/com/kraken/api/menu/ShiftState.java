package com.kraken.api.menu;

/**
 * When a {@link MenuRule} applies, by the Shift key, which the hook reads as it rebuilds the menu.
 */
public enum ShiftState {

    /**
     * Whether or not Shift is held.
     */
    ANY,

    /**
     * Only while Shift is held.
     */
    HELD,

    /**
     * Only while Shift isn't held.
     */
    NOT_HELD
}
