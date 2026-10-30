package com.kraken.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which withdraw option a Shift-click runs on a bank item: the op number of the option in the bank and in a Chambers
 * of Xeric storage unit (which has no all-but-one or placeholder option, so those withdraw all there), 0 for none.
 */
@Getter
@RequiredArgsConstructor
public enum ShiftWithdrawMode {
    WITHDRAW_1("Withdraw-1", 2, 1),
    WITHDRAW_5("Withdraw-5", 3, 2),
    WITHDRAW_10("Withdraw-10", 4, 3),
    WITHDRAW_X("Withdraw-X", 5, 5),
    WITHDRAW_ALL("Withdraw-All", 7, 4),
    WITHDRAW_ALL_BUT_1("Withdraw-All-But-1", 8, 4),
    WITHDRAW_PLACEHOLDER("Placeholder", 10, 4),
    OFF("Off", 0, 0);

    private final String name;
    private final int identifier;
    private final int identifierChambersStorageUnit;

    @Override
    public String toString() {
        return name;
    }
}
