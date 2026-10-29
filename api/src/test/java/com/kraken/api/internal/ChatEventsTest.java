package com.kraken.api.internal;

import com.kraken.api.ChatMessageType;
import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MessageNode;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.ChatMessage;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat events: a {@link ChatMessage} per line the client adds to its chat history, found by id from the history's
 * next-id counter.
 */
class ChatEventsTest {

    private GameFixture game;
    private EventBus bus;
    private EventSource source;
    private final List<ChatMessage> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        Client client = new Client(game.memory, game.map);
        bus = new EventBus();
        source = new EventSource(client, bus);
        bus.register(ChatMessage.class, seen::add, 0);
    }

    private List<String> texts() {
        return seen.stream().map(ChatMessage::getMessage).toList();
    }

    @Test
    void aNewLinePostsItsMessageAndTheLinesAlreadyThereDoNot() {
        source.poll();
        assertEquals(List.of(), seen, "the welcome lines were there before tracking started");

        game.setCycle(170);
        int id = game.addChatLine(9, "<img=2>Bob", "anyone selling logs?", "Woodcutters");
        source.poll();

        assertEquals(1, seen.size());
        ChatMessage message = seen.get(0);
        assertEquals(ChatMessageType.FRIENDSCHAT, message.getType());
        assertEquals("<img=2>Bob", message.getName());
        assertEquals("anyone selling logs?", message.getMessage());
        assertEquals("Woodcutters", message.getSender());
        MessageNode node = message.getMessageNode();
        assertEquals(id, node.getId());
        assertEquals(170, node.getCycle());
        assertEquals("anyone selling logs?", node.getValue());
        assertEquals(message.getTimestamp(), node.getTimestamp());
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - message.getTimestamp()) < 60, "stamped when read");

        source.poll();
        assertEquals(1, seen.size(), "a line posts once");
    }

    @Test
    void linesAddedBetweenPollsPostInTheOrderTheyCame() {
        source.poll();
        game.addChatLine(0, "", "You catch a shrimp.", "");
        game.addChatLine(0, "", GameFixture.LONG_TEXT, "");
        game.addChatLine(2, "Zezima", "nice", "");

        source.onNative(new NativeEvent("ON_CHAT_TRANSMIT", 14, new long[4], Map.of(), 1));
        source.poll();

        assertEquals(List.of("You catch a shrimp.", GameFixture.LONG_TEXT, "nice"), texts());
        assertEquals(ChatMessageType.PUBLICCHAT, seen.get(2).getType());
    }

    @Test
    void aClearedHistoryMakesEveryLineFromTheFirstIdNew() {
        source.poll();
        game.clearChat();
        source.poll();
        assertEquals(List.of(), seen, "clearing posts nothing");

        game.addChatLine(0, "", GameFixture.WELCOME, "");
        source.poll();
        assertEquals(List.of(GameFixture.WELCOME), texts());
        assertEquals(0, seen.get(0).getMessageNode().getId());
    }

    @Test
    void aCounterThatWentBackBetweenPollsStartsFromTheFirstId() {
        game.addChatLine(0, "", "one", "");
        game.addChatLine(0, "", "two", "");
        source.poll();

        game.clearChat();
        game.addChatLine(0, "", GameFixture.WELCOME, "");
        game.addChatLine(2, "Zezima", "back", "");
        source.poll();

        assertEquals(List.of(GameFixture.WELCOME, "back"), texts());
    }

    @Test
    void anUnknownTypeIsUnknown() {
        source.poll();
        game.addChatLine(250, "", "?", "");
        source.poll();
        assertEquals(ChatMessageType.UNKNOWN, seen.get(0).getType());
    }
}
