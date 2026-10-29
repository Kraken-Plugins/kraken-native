package com.kraken.api;

import lombok.Value;

/**
 * A line of the client's chat history, as it was read just after the client added it. A snapshot: it doesn't follow
 * the line afterwards, and the client reuses a line's slot once 100 newer lines of its type have arrived.
 */
@Value
public class MessageNode {

    /**
     * The line's id: the client numbers its chat lines in the order they arrive, across every type.
     */
    int id;

    /**
     * The kind of line.
     */
    ChatMessageType type;

    /**
     * Who sent the line, with any icon tags (such as {@code <img=2>}); empty for game messages.
     */
    String name;

    /**
     * The channel the line came through: the friends chat's or the clan's name; empty for most types.
     */
    String sender;

    /**
     * The line's text, with its colour and image tags.
     */
    String value;

    /**
     * When the line was read, in seconds since the epoch.
     */
    int timestamp;

    /**
     * The client cycle the line arrived on ({@link Client#getGameCycle()}).
     */
    int cycle;
}
