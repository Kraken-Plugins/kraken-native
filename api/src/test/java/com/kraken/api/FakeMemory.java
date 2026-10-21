package com.kraken.api;

import com.kraken.api.memory.Memory;
import com.kraken.api.memory.MemoryException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An in-memory {@link Memory} for tests: a set of segments at chosen absolute addresses, so a tiny
 * Client/World/Actor object graph can be laid out by hand and read back through the real offset chains.
 */
public final class FakeMemory implements Memory {

    private record Segment(long start, byte[] data) {
    }

    private final List<Segment> segments = new ArrayList<>();
    private long moduleBase;

    public void setModuleBase(long base) {
        this.moduleBase = base;
    }

    /** Reserves {@code size} bytes at absolute address {@code start}. */
    public void alloc(long start, int size) {
        segments.add(new Segment(start, new byte[size]));
    }

    private Segment segmentFor(long address, int length) {
        for (Segment segment : segments) {
            if (address >= segment.start() && address + length <= segment.start() + segment.data().length) {
                return segment;
            }
        }
        throw new MemoryException("No fake segment covers 0x" + Long.toHexString(address) + " len " + length);
    }

    public void putInt(long address, int value) {
        Segment segment = segmentFor(address, 4);
        int off = (int) (address - segment.start());
        for (int i = 0; i < 4; i++) {
            segment.data()[off + i] = (byte) (value >>> (8 * i));
        }
    }

    public void putLong(long address, long value) {
        Segment segment = segmentFor(address, 8);
        int off = (int) (address - segment.start());
        for (int i = 0; i < 8; i++) {
            segment.data()[off + i] = (byte) (value >>> (8 * i));
        }
    }

    public void putShort(long address, int value) {
        putBytes(address, new byte[]{(byte) value, (byte) (value >>> 8)});
    }

    public void putByte(long address, int value) {
        Segment segment = segmentFor(address, 1);
        segment.data()[(int) (address - segment.start())] = (byte) value;
    }

    public void putBytes(long address, byte[] value) {
        Segment segment = segmentFor(address, value.length);
        int off = (int) (address - segment.start());
        System.arraycopy(value, 0, segment.data(), off, value.length);
    }

    /** Writes an EASTL small-string (inline text, length encoded in the flag byte at +0x17). */
    public void putSsoString(long address, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        putBytes(address, bytes);
        putByte(address + 0x17, 0x17 - bytes.length);
    }

    @Override
    public byte[] read(long address, int length) {
        Segment segment = segmentFor(address, length);
        int off = (int) (address - segment.start());
        return Arrays.copyOfRange(segment.data(), off, off + length);
    }

    @Override
    public long moduleBase() {
        return moduleBase;
    }

    @Override
    public long moduleSize() {
        return 0x2303000;
    }

    @Override
    public boolean isValid() {
        return true;
    }
}
