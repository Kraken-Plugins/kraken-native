package com.kraken.api.action;

import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarbitID;

/**
 * The prayers, each by its component in the prayer book ({@link InterfaceID.Prayerbook}) and the varbit that is 1
 * while it is on ({@link VarbitID}).
 */
public enum Prayer {
    ;

    private final int componentId;
    private final int varbit;

    Prayer(int componentId, int varbit) {
        this.componentId = componentId;
        this.varbit = varbit;
    }

    /**
     * The prayer's component in the prayer book.
     *
     * @return the packed component id, an {@link InterfaceID.Prayerbook} constant
     */
    public int getComponentId() {
        return componentId;
    }

    /**
     * The varbit that is 1 while the prayer is on.
     *
     * @return the varbit id, a {@link VarbitID} constant
     */
    public int getVarbit() {
        return varbit;
    }
}
