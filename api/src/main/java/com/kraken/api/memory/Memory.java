package com.kraken.api.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Read-only view of the game process's address space. Addresses are absolute runtime addresses
 * (module base + RVA, or a pointer previously read out of memory). The client is x64, so all
 * multi-byte values are little-endian.
 *
 * <p>Implementations read across the process boundary and may fail for an unmapped or freed address;
 * those failures surface as {@link MemoryException}. Callers that walk pointer chains should guard
 * against null (zero) pointers before dereferencing.
 */
public interface Memory {

    /** Reads {@code length} bytes starting at {@code address}, or throws {@link MemoryException}. */
    byte[] read(long address, int length);

    /** Base address the {@code osclient.exe} module is loaded at this run (RVAs are added to this). */
    long moduleBase();

    /** Size of the loaded module image in bytes. */
    long moduleSize();

    /** Whether the target process is still alive and readable. */
    boolean isValid();

    default long readPointer(long address) {
        return readLong(address);
    }

    default long readLong(long address) {
        return buffer(address, 8).getLong();
    }

    default int readInt(long address) {
        return buffer(address, 4).getInt();
    }

    default int readShort(long address) {
        return buffer(address, 2).getShort() & 0xffff;
    }

    default int readU8(long address) {
        return read(address, 1)[0] & 0xff;
    }

    private ByteBuffer buffer(long address, int length) {
        return ByteBuffer.wrap(read(address, length)).order(ByteOrder.LITTLE_ENDIAN);
    }
}
