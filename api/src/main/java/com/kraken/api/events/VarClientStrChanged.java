package com.kraken.api.events;

import lombok.Value;

/**
 * A string varc (a client variable, {@code gameval.VarClientID}) changed; {@code Client.getVarcStrValue(index)} gives
 * its new value.
 *
 * <p>With the native hook connected, it is posted as soon as a script sets the varc to a new value, but the API reads
 * the value to tell whether it changed, so a varc set several times between two polls posts once. Without the hook,
 * the API compares the varcs every poll. Either way a script setting a varc to the value it already holds posts
 * nothing, and varcs the client drops or loads itself post too: logging out drops those it doesn't save, logging in
 * loads the saved ones. Tracking that starts posts nothing for the current values.
 */
@Value
public class VarClientStrChanged {

    /**
     * The varc that changed, e.g. {@code VarClientID.CHATINPUT}.
     */
    int index;
}
