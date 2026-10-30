package com.kraken.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which deposit option a Shift-click runs on an inventory item while a bank, deposit box, group storage or Chambers
 * of Xeric storage unit is open: the op numbers of the option on each interface, 0 for none.
 */
@Getter
@RequiredArgsConstructor
public enum ShiftDepositMode {
    DEPOSIT_1("Deposit-1", 3, 2, 2, 1),
    DEPOSIT_5("Deposit-5", 4, 3, 3, 2),
    DEPOSIT_10("Deposit-10", 5, 4, 4, 3),
    DEPOSIT_X("Deposit-X", 6, 5, 5, 5),
    DEPOSIT_ALL("Deposit-All", 8, 6, 7, 4),
    EXTRA_OP("Eat/Wield/Etc.", 9, 9, 0, 0),
    OFF("Off", 0, 0, 0, 0);

    private final String name;
    private final int identifier;
    private final int identifierDepositBox;
    private final int identifierGroupStorage;
    private final int identifierChambersStorageUnit;

    @Override
    public String toString() {
        return name;
    }
}
