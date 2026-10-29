package com.kraken.api.internal;

/**
 * Internal. An actor's overhead text at one read, with its timer, which the overhead text events compare poll to poll.
 * The client counts the timer down by one per actor step and empties the text when it reaches 0.
 *
 * @param text   the text; "" when none shows
 * @param cycles the client cycles the text has left to show
 */
public record OverheadText(String text, int cycles) {
}
