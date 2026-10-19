package com.kraken.api.config;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one config file's bytes the way the client's decoders do: big-endian integers, the OSRS "smart" encodings and
 * zero-terminated strings. Every read past the end throws {@link ConfigFormatException}, so a decoder that meets an
 * opcode it doesn't know fails loudly instead of returning garbage.
 */
public final class ConfigBuffer {

    /** Config strings are Windows-1252, terminated by a zero byte. */
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final byte[] data;
    private int pos;

    public ConfigBuffer(byte[] data) {
        this.data = data;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return data.length - pos;
    }

    public boolean hasRemaining() {
        return pos < data.length;
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    /** Unsigned byte. */
    public int g1() {
        need(1);
        return data[pos++] & 0xff;
    }

    /** Signed byte. */
    public int g1s() {
        need(1);
        return data[pos++];
    }

    /** Unsigned 16-bit. */
    public int g2() {
        need(2);
        int value = (data[pos] & 0xff) << 8 | data[pos + 1] & 0xff;
        pos += 2;
        return value;
    }

    /** Signed 16-bit. */
    public int g2s() {
        return (short) g2();
    }

    /** Unsigned 24-bit. */
    public int g3() {
        need(3);
        int value = (data[pos] & 0xff) << 16 | (data[pos + 1] & 0xff) << 8 | data[pos + 2] & 0xff;
        pos += 3;
        return value;
    }

    /** Signed 32-bit. */
    public int g4() {
        need(4);
        int value = (data[pos] & 0xff) << 24 | (data[pos + 1] & 0xff) << 16 | (data[pos + 2] & 0xff) << 8
                | data[pos + 3] & 0xff;
        pos += 4;
        return value;
    }

    /** Signed 64-bit. */
    public long g8() {
        return (long) g4() << 32 | g4() & 0xffffffffL;
    }

    /** Unsigned smart: one byte (0..127) or two bytes minus 0x8000 (128..32767). */
    public int gSmart1or2() {
        return peek() < 128 ? g1() : g2() - 0x8000;
    }

    /** Signed smart: one byte minus 64 (-64..63) or two bytes minus 0xC000 (-16384..16383). */
    public int gSmart1or2s() {
        return peek() < 128 ? g1() - 64 : g2() - 0xC000;
    }

    /**
     * "Big smart" (model and other large ids): two bytes when the top bit is clear (0x7FFF meaning -1), otherwise four
     * bytes with the top bit masked off.
     */
    public int gBigSmart() {
        if (peek() >= 128) {
            return g4() & 0x7fffffff;
        }
        int value = g2();
        return value == 0x7fff ? -1 : value;
    }

    /** A zero-terminated Windows-1252 string. */
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
     * The params block shared by several config types (opcode 249): a count, then per entry a value tag, a 24-bit key
     * and the value. As the client reads it (0x5fb880 in 241.3): tag 1 is a string, tag 2 a 64-bit value, anything
     * else a 32-bit int. Values are {@code String}, {@code Long} or {@code Integer}; order is kept.
     */
    public Map<Integer, Object> params() {
        int count = g1();
        Map<Integer, Object> params = new LinkedHashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            int tag = g1();
            int key = g3();
            params.put(key, switch (tag) {
                case 1 -> gjstr();
                case 2 -> g8();
                default -> g4();
            });
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
