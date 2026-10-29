package com.kraken.api.events;

import com.kraken.api.ChatMessageType;
import com.kraken.api.MessageNode;
import lombok.Value;

/**
 * A line was added to the chat history: one event per line, in the order the client added them. The API reads the
 * client's next line id every poll, and right away when the native hook reports the client's chat raise. Lines
 * already there when tracking starts post nothing.
 */
@Value
public class ChatMessage {

    /**
     * The line as it was read.
     */
    MessageNode messageNode;

    /**
     * The kind of line.
     */
    ChatMessageType type;

    /**
     * Who sent it, with any icon tags; empty for game messages.
     */
    String name;

    /**
     * The text, with its colour and image tags.
     */
    String message;

    /**
     * The channel it came through: the friends chat's or the clan's name; empty for most types.
     */
    String sender;

    /**
     * When the line was read, in seconds since the epoch.
     */
    int timestamp;
}
