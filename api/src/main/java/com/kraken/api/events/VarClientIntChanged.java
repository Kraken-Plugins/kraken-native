package com.kraken.api.events;

import lombok.Value;

/**
 * An int varc (a client variable, {@code gameval.VarClientID}) changed; {@code Client.getVarcIntValue(index)} gives its
 * new value.
 *
 * <p>With the native hook connected, each change a script makes posts one, in order, even when several come between
 * two polls. Without the hook, the API compares the varcs every poll, so a varc changed and changed back between two
 * polls posts nothing. Either way a script setting a varc to the value it already holds posts nothing, and varcs the
 * client drops or loads itself post too: logging out drops those it doesn't save, logging in loads the saved ones.
 * Tracking that starts posts nothing for the current values.
 */
@Value
public class VarClientIntChanged {

    /**
     * The varc that changed, e.g. {@code VarClientID.MESLAYERMODE}.
     */
    int index;
}
