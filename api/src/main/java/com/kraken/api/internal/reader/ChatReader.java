package com.kraken.api.internal.reader;

import com.kraken.api.ChatMessageType;
import com.kraken.api.MessageNode;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

/**
 * Reads the client's chat history: a global counter with the next line's id, and a global hash map from a line's id
 * to the line ({@code ChatLine}: the type, the cycle it arrived on, and the name, text and sender as eastl strings).
 * The client adds every line through one function, which takes the id from the counter, so the lines added since a
 * read are the ids from that read's counter up to the current one. Internal: the chat events read through it.
 *
 * <p>The keys are optional: with a mapping that lacks them, {@link #available()} is false and every read gives its
 * "missing" value.
 */
public final class ChatReader extends GameReader {

    private static final long MAX_BUCKETS = 1 << 20;
    private static final int MAX_CHAIN = 10_000;

    private final long nextIdRva;
    private final long bucketsRva;
    private final long bucketCountRva;
    private final int nodeKey;
    private final int nodeValue;
    private final int nodeNext;
    private final int lineType;
    private final int lineCycle;
    private final int lineName;
    private final int lineText;
    private final int lineSender;
    private final int lineSize;

    /**
     * Resolves the chat keys.
     *
     * @param memory  the client process's memory
     * @param mapping the client build's mapping
     */
    public ChatReader(Memory memory, Mapping mapping) {
        super(memory, mapping);
        this.nextIdRva = optionalGlobal("chatLines.nextId");
        this.bucketsRva = optionalGlobal("chatLines.buckets");
        this.bucketCountRva = optionalGlobal("chatLines.bucketCount");
        this.nodeKey = containerOr("hash_map.node.key", 0);
        this.nodeValue = containerOr("hash_map.node.value", 0x10);
        this.nodeNext = containerOr("hash_map.node.next", 0x18);
        this.lineType = optionalOffset("ChatLine.type");
        this.lineCycle = optionalOffset("ChatLine.cycle");
        this.lineName = optionalOffset("ChatLine.name");
        this.lineText = optionalOffset("ChatLine.text");
        this.lineSender = optionalOffset("ChatLine.sender");
        List<Integer> fields = List.of(lineType, lineCycle, lineName, lineText, lineSender);
        this.lineSize = Collections.max(fields) + structs.stringSize();
    }

    /**
     * Whether this build's mapping has the chat history.
     *
     * @return true if lines can be read
     */
    public boolean available() {
        return nextIdRva >= 0 && bucketsRva >= 0 && bucketCountRva >= 0 && lineType >= 0 && lineCycle >= 0
                && lineName >= 0 && lineText >= 0 && lineSender >= 0;
    }

    /**
     * The id the client gives the next chat line.
     *
     * @return the id, or -1 if it can't be read
     */
    public int nextId() {
        return available() ? safeInt(memory.moduleBase() + nextIdRva, -1) : -1;
    }

    /**
     * A chat line by its id. The same walk as the client's: the bucket is the id modulo the bucket count's low 32
     * bits, then the chain is followed until a node holds the id.
     *
     * @param id        the line's id
     * @param timestamp the time to stamp the line with, in seconds since the epoch
     * @return the line, or null if no line has that id (the client reused it, or it never came) or it can't be read
     */
    public MessageNode line(int id, int timestamp) {
        long line = find(id);
        if (line == 0) {
            return null;
        }
        byte[] block;
        try {
            block = memory.read(line, lineSize);
        } catch (MemoryException e) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        int type = buffer.getInt(lineType);
        return new MessageNode(id, ChatMessageType.of(type), structs.string(block, line, lineName),
                structs.string(block, line, lineSender), structs.string(block, line, lineText), timestamp,
                buffer.getInt(lineCycle));
    }

    private long find(int id) {
        if (!available() || id < 0) {
            return 0;
        }
        long base = memory.moduleBase();
        long buckets = safePointer(base + bucketsRva);
        long count = safeLong(base + bucketCountRva, 0) & 0xffffffffL;
        if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
            return 0;
        }
        long node = safePointer(buckets + (id % count) * 8);
        for (int guard = 0; node != 0 && guard < MAX_CHAIN; guard++) {
            if (safeInt(node + nodeKey, -1) == id) {
                return safePointer(node + nodeValue);
            }
            node = safePointer(node + nodeNext);
        }
        return 0;
    }
}
