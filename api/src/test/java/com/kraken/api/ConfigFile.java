package com.kraken.api;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Builds a config file's bytes for tests: {@code new ConfigFile().op(2).str("Coins").op(11).end()}.
 */
public final class ConfigFile {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public ConfigFile op(int opcode) {
        return g1(opcode);
    }

    public ConfigFile g1(int v) {
        out.write(v);
        return this;
    }

    public ConfigFile g2(int v) {
        return g1(v >> 8).g1(v);
    }

    public ConfigFile g3(int v) {
        return g1(v >> 16).g2(v);
    }

    public ConfigFile g4(int v) {
        return g2(v >> 16).g2(v);
    }

    public ConfigFile str(String s) {
        out.writeBytes(s.getBytes(Charset.forName("windows-1252")));
        return g1(0);
    }

    /**
     * Adds the end opcode and returns the bytes.
     *
     * @return the finished file
     */
    public byte[] end() {
        out.write(0);
        return out.toByteArray();
    }

    /**
     * A varbit file: varp {@code baseVar}, bits {@code start} to {@code end}.
     *
     * @param baseVar the varp holding the varbit
     * @param start   the lowest bit
     * @param end     the highest bit (inclusive)
     * @return the varbit file's bytes
     */
    public static byte[] varbit(int baseVar, int start, int end) {
        return new ConfigFile().op(1).g2(baseVar).g1(start).g1(end).end();
    }
}
