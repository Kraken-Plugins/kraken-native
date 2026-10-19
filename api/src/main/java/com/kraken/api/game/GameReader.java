package com.kraken.api.game;

import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.struct.StructReader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared plumbing for the classes that mirror a Lua namespace ({@link Client} for {@code ClientOp},
 * {@link ClientState} for {@code ClientState}): the memory and mapping they read through, and tolerant reads that turn
 * an unreadable address into a fallback value instead of an exception.
 *
 * <p>Every chain starts at the global {@code Client} pointer ({@code moduleBase + globals.client}).
 */
abstract class GameReader {

    protected final Memory memory;
    protected final Mapping mapping;
    protected final StructReader structs;
    private final long clientRva;

    GameReader(Memory memory, Mapping mapping) {
        this.memory = memory;
        this.mapping = mapping;
        this.structs = new StructReader(memory, mapping);
        this.clientRva = mapping.global("client");
    }

    public Mapping mapping() {
        return mapping;
    }

    /** The {@code Client} object, or 0 if the pointer can't be read (client still booting). */
    protected final long clientBase() {
        return safePointer(memory.moduleBase() + clientRva);
    }

    /** A mapped struct field's offset, or -1 if this build's mapping doesn't have it. */
    protected final int optionalOffset(String key) {
        return mapping.has(key) ? mapping.offset(key) : -1;
    }

    protected final long safePointer(long address) {
        try {
            return memory.readPointer(address);
        } catch (MemoryException e) {
            return 0;
        }
    }

    protected final long safeLong(long address, long fallback) {
        try {
            return memory.readLong(address);
        } catch (MemoryException e) {
            return fallback;
        }
    }

    protected final int safeInt(long address, int fallback) {
        try {
            return memory.readInt(address);
        } catch (MemoryException e) {
            return fallback;
        }
    }

    protected final int safeU8(long address, int fallback) {
        try {
            return memory.readU8(address);
        } catch (MemoryException e) {
            return fallback;
        }
    }

    /** {@code count} consecutive little-endian ints in one read, or null if they can't be read. */
    protected final int[] readInts(long address, int count) {
        if (address == 0 || count <= 0) {
            return count == 0 ? new int[0] : null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(memory.read(address, count * 4)).order(ByteOrder.LITTLE_ENDIAN);
            int[] values = new int[count];
            buffer.asIntBuffer().get(values);
            return values;
        } catch (MemoryException e) {
            return null;
        }
    }

    /** {@code length} bytes as a little-endian buffer, or null if they can't be read. */
    protected final ByteBuffer readBlock(long address, int length) {
        if (address == 0 || length <= 0) {
            return null;
        }
        try {
            return ByteBuffer.wrap(memory.read(address, length)).order(ByteOrder.LITTLE_ENDIAN);
        } catch (MemoryException e) {
            return null;
        }
    }
}
