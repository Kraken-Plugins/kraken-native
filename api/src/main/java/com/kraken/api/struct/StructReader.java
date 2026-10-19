package com.kraken.api.struct;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads the two client container types the entity API needs, using layouts from the {@link Mapping}:
 * the 24-byte EASTL string (small-string-optimised) and the EASTL hash_map used for the player and
 * NPC tables. Kept separate from game semantics so it can be unit-tested over a fake memory image.
 */
public final class StructReader {

    private static final int MAX_STRING = 4096;
    private static final long MAX_BUCKETS = 1 << 24;
    private static final int MAX_CHAIN = 100_000; // guards against a corrupt/cyclic bucket chain

    private final Memory memory;

    // eastl::string: 24 bytes. byte[flagByte] top bit (flagMask) set => heap form {char* at +0, u64 size at +8};
    // otherwise the text is inline at +0 and its length is (ssoCapacity - flagByte).
    private final int strFlagByte;
    private final int strFlagMask;
    private final int strSsoCapacity;
    private final int strSize;

    // eastl::hash_map: buckets pointer and bucket count at these offsets from the map object;
    // each node is {u32 key, ..., value, next-node}.
    private final int mapBuckets;
    private final int mapBucketCount;
    private final int nodeKey;
    private final int nodeValue;
    private final int nodeNext;

    public StructReader(Memory memory, Mapping mapping) {
        this.memory = memory;
        this.strFlagByte = mapping.container("string.heapFlagByte");
        this.strFlagMask = mapping.container("string.heapFlagMask");
        this.strSsoCapacity = mapping.container("string.ssoCapacity");
        this.strSize = mapping.container("string.size");
        this.mapBuckets = mapping.container("hash_map.buckets");
        this.mapBucketCount = mapping.container("hash_map.bucketCount");
        this.nodeKey = mapping.container("hash_map.node.key");
        this.nodeValue = mapping.container("hash_map.node.value");
        this.nodeNext = mapping.container("hash_map.node.next");
    }

    /** Decodes the EASTL string object located at {@code address}. Returns "" for a null or unreadable string. */
    public String string(long address) {
        if (address == 0) {
            return "";
        }
        try {
            int flag = memory.readU8(address + strFlagByte);
            long textAddress;
            int length;
            if ((flag & strFlagMask) != 0) {
                textAddress = memory.readPointer(address);
                length = (int) memory.readLong(address + 8);
            } else {
                textAddress = address;
                length = strSsoCapacity - flag;
            }
            if (length <= 0 || length > MAX_STRING || textAddress == 0) {
                return "";
            }
            byte[] bytes = memory.read(textAddress, length);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (MemoryException e) {
            return "";
        }
    }

    /**
     * The size of an EASTL string object, the stride of an array of them.
     *
     * @return the size in bytes
     */
    public int stringSize() {
        return strSize;
    }

    /**
     * Decodes an EASTL string held in a block of a struct read earlier: inline text comes from the block, heap text
     * is read from memory.
     *
     * @param block  the struct's bytes
     * @param offset where the string object starts in the block
     * @return the text, or "" if the block doesn't cover the string or the text can't be read
     */
    public String string(byte[] block, int offset) {
        if (offset < 0 || offset + strSize > block.length) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        int flag = block[offset + strFlagByte] & 0xff;
        if ((flag & strFlagMask) == 0) {
            int length = strSsoCapacity - flag;
            return length <= 0 ? "" : new String(block, offset, length, StandardCharsets.UTF_8);
        }
        long textAddress = buffer.getLong(offset);
        long length = buffer.getLong(offset + 8);
        if (length <= 0 || length > MAX_STRING || textAddress == 0) {
            return "";
        }
        try {
            return new String(memory.read(textAddress, (int) length), StandardCharsets.UTF_8);
        } catch (MemoryException e) {
            return "";
        }
    }

    /**
     * Looks up {@code key} in the hash_map object at {@code mapAddress} and returns the stored value pointer
     * (here, the address of the Player/Npc), or 0 if the key is absent or the map looks corrupt.
     */
    public long hashGet(long mapAddress, int key) {
        if (mapAddress == 0) {
            return 0;
        }
        try {
            long buckets = memory.readPointer(mapAddress + mapBuckets);
            long count = memory.readLong(mapAddress + mapBucketCount);
            if (buckets == 0 || count <= 0 || count > MAX_BUCKETS) {
                return 0;
            }
            long bucket = Integer.toUnsignedLong(key) % count;
            long node = memory.readPointer(buckets + bucket * 8);
            for (int guard = 0; node != 0 && guard < MAX_CHAIN; guard++) {
                if (memory.readInt(node + nodeKey) == key) {
                    return memory.readPointer(node + nodeValue);
                }
                node = memory.readPointer(node + nodeNext);
            }
            return 0;
        } catch (MemoryException e) {
            return 0;
        }
    }
}
