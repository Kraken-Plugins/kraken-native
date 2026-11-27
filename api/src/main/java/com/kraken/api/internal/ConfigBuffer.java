package com.kraken.api.internal;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one config file's bytes the way the client's decoders do: big-endian integers, the OSRS "smart" encodings and
 * zero-terminated strings. Every read past the end throws {@link ConfigFormatException}, so a decoder that meets an
 * opcode it doesn't know fails loudly instead of returning garbage.
 */
public final class ConfigBuffer {

    /**
     * Config strings are Windows-1252, terminated by a zero byte.
     */
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final byte[] data;
    private int pos;

    /**
     * Starts reading at the first byte.
     *
     * @param data the file's bytes
     */
    public ConfigBuffer(byte[] data) {
        this.data = data;
    }

    /**
     * Where the next read starts.
     *
     * @return the offset of the next byte to read
     */
    public int position() {
        return pos;
    }

    /**
     * How many bytes are left.
     *
     * @return the number of unread bytes
     */
    public int remaining() {
        return data.length - pos;
    }

    /**
     * Whether any bytes are left.
     *
     * @return true if at least one byte is unread
     */
    public boolean hasRemaining() {
        return pos < data.length;
    }

    /**
     * Moves past bytes without reading them.
     *
     * @param n how many bytes to skip
     * @throws ConfigFormatException if fewer than {@code n} bytes are left
     */
    public void skip(int n) {
        need(n);
        pos += n;
    }

    /**
     * Reads an unsigned byte.
     *
     * @return the byte, 0 to 255
     */
    public int g1() {
        need(1);
        return data[pos++] & 0xff;
    }

    /**
     * Reads a signed byte.
     *
     * @return the byte, -128 to 127
     */
    public int g1s() {
        need(1);
        return data[pos++];
    }

    /**
     * Reads an unsigned 16-bit value.
     *
     * @return the value, 0 to 65535
     */
    public int g2() {
        need(2);
        int value = (data[pos] & 0xff) << 8 | data[pos + 1] & 0xff;
        pos += 2;
        return value;
    }

    /**
     * Reads a signed 16-bit value.
     *
     * @return the value, -32768 to 32767
     */
    public int g2s() {
        return (short) g2();
    }

    /**
     * Reads an unsigned 24-bit value.
     *
     * @return the value, 0 to 16777215
     */
    public int g3() {
        need(3);
        int value = (data[pos] & 0xff) << 16 | (data[pos + 1] & 0xff) << 8 | data[pos + 2] & 0xff;
        pos += 3;
        return value;
    }

    /**
     * Reads a signed 24-bit value (values above 0x7FFFFF wrap below 0, so 0xFFFFFF is -1).
     *
     * @return the value, -8388608 to 8388607
     */
    public int g3s() {
        int value = g3();
        return value > 0x7fffff ? value - 0x1000000 : value;
    }

    /**
     * Reads a signed 32-bit value.
     *
     * @return the value
     */
    public int g4() {
        need(4);
        int value = (data[pos] & 0xff) << 24 | (data[pos + 1] & 0xff) << 16 | (data[pos + 2] & 0xff) << 8
                | data[pos + 3] & 0xff;
        pos += 4;
        return value;
    }

    /**
     * Reads a signed 64-bit value.
     *
     * @return the value
     */
    public long g8() {
        return (long) g4() << 32 | g4() & 0xffffffffL;
    }

    /**
     * Reads an unsigned smart: one byte (0..127) or two bytes minus 0x8000 (128..32767).
     *
     * @return the value, 0 to 32767
     */
    public int gSmart1or2() {
        return peek() < 128 ? g1() : g2() - 0x8000;
    }

    /**
     * Reads a signed smart: one byte minus 64 (-64..63) or two bytes minus 0xC000 (-16384..16383).
     *
     * @return the value, -16384 to 16383
     */
    public int gSmart1or2s() {
        return peek() < 128 ? g1() - 64 : g2() - 0xC000;
    }

    /**
     * Reads a "big smart" (model and other large ids): two bytes when the top bit is clear (0x7FFF meaning -1),
     * otherwise four bytes with the top bit masked off.
     *
     * @return the value, -1 for the two-byte 0x7FFF
     */
    public int gBigSmart() {
        if (peek() >= 128) {
            return g4() & 0x7fffffff;
        }
        int value = g2();
        return value == 0x7fff ? -1 : value;
    }

    /**
     * Reads a zero-terminated Windows-1252 string.
     *
     * @return the text before the terminator (the terminator is consumed)
     * @throws ConfigFormatException if the data ends before a zero byte
     */
    public String gjstr() {
        int start = pos;
        while (true) {
            need(1);
            if (data[pos++] == 0) {
                break;
            }
        }
        return new String(data, start, pos - start - 1, CP1252);
    }

    /**
     * Reads the params block shared by several config types (opcode 249): a count, then per entry a value tag, a 24-bit
     * key and the value. As the client reads it (0x5fb880 in 241.3): tag 1 is a string, tag 2 a 64-bit value, anything
     * else a 32-bit int.
     *
     * @return param id to value ({@code String}, {@code Long} or {@code Integer}), in file order
     */
    public Map<Integer, Object> params() {
        int count = g1();
        Map<Integer, Object> params = new LinkedHashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            int tag = g1();
            int key = g3();
            Object value;
            if (tag == 1) {
                value = gjstr();
            } else if (tag == 2) {
                value = Long.valueOf(g8());
            } else {
                value = Integer.valueOf(g4());
            }
            params.put(key, value);
        }
        return params;
    }

    private int peek() {
        need(1);
        return data[pos] & 0xff;
    }

    private void need(int n) {
        if (n < 0 || pos + n > data.length) {
            throw new ConfigFormatException("read of " + n + " at " + pos + " past the end (" + data.length + " bytes)");
        }
    }
}
