package com.kraken.api.events;

import lombok.Value;

/**
 * The game gained or lost the keyboard focus: alt-tabbing away or back, clicking another window, or clicking a
 * control beside the game in a window it is embedded in. The API compares {@code Client.isFocused()} every poll;
 * tracking that starts posts nothing for the current state.
 */
@Value
public class FocusChanged {

    /**
     * Whether the game has the keyboard focus now.
     */
    boolean focused;
}
