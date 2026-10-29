package com.kraken.api.internal.event;

import com.kraken.api.MessageNode;
import com.kraken.api.events.ChatMessage;
import com.kraken.api.internal.reader.ChatReader;

import java.util.Set;

/**
 * {@link ChatMessage} for each line the client added to its chat history since the last read, oldest first. The
 * client numbers its lines with a counter, so the new lines are the ids from the counter's last value up to its
 * current one, each looked up in the history. Reads the counter (one int) every poll, and at once after the client's
 * {@code ON_CHAT_TRANSMIT} raise, which follows every line it adds. The client clears its history, and sets the
 * counter back to 0, on logout; a counter that went back makes every line from 0 new. Lines already there when
 * tracking starts post nothing.
 */
final class ChatTracker extends Tracker {

    /**
     * The most lines one read posts: after a long stall, the newest this many.
     */
    private static final int MAX_LINES = 200;

    private int last = -1;

    ChatTracker(TrackerContext context) {
        super(context, ChatMessage.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_CHAT_TRANSMIT");
    }

    @Override
    boolean baseline(boolean replay) {
        ChatReader chat = client.getChatReader();
        if (!chat.available()) {
            return true;
        }
        last = chat.nextId();
        return last >= 0;
    }

    @Override
    void update(boolean ticked) {
        ChatReader chat = client.getChatReader();
        int next = chat.nextId();
        if (next < 0 || next == last) {
            return;
        }
        int from = Math.max(next < last ? 0 : last, next - MAX_LINES);
        last = next;
        int now = (int) (System.currentTimeMillis() / 1000);
        for (int id = from; id < next; id++) {
            MessageNode line = chat.line(id, now);
            if (line != null) {
                bus.post(new ChatMessage(line, line.getType(), line.getName(), line.getValue(), line.getSender(),
                        line.getTimestamp()));
            }
        }
    }

    @Override
    void forget() {
        last = -1;
    }
}
