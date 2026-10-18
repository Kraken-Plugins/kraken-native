package com.kraken.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies an {@code osclient.exe} build. {@link #version()} (e.g. {@code "241.3"}) comes from the exe's Windows
 * version resource and names the mapping file; {@link #sha256()} must match the hash recorded in that mapping, so a
 * mapping is never applied to a different binary that happens to carry the same version.
 *
 * <p>The updater reads the version the same way ({@code native_updater/pe.py: file_version}); keep the two in step.
 */
public record ClientBuild(String version, String sha256) {

    // VS_FIXEDFILEINFO starts with this signature (0xFEEF04BD) and struct version (0x00010000), little-endian;
    // dwFileVersionMS follows 4 bytes later: major in the high word, minor in the low word.
    private static final byte[] FIXED_FILE_INFO = {(byte) 0xBD, 0x04, (byte) 0xEF, (byte) 0xFE, 0x00, 0x00, 0x01, 0x00};

    public static ClientBuild of(Path exe) throws IOException {
        return of(Files.readAllBytes(exe));
    }

    static ClientBuild of(byte[] exe) {
        return new ClientBuild(version(exe), sha256(exe));
    }

    /** {@code "<major>.<minor>"} of the file version, e.g. {@code "241.3"}. */
    static String version(byte[] exe) {
        int at = indexOf(exe, FIXED_FILE_INFO);
        if (at < 0 || at + 12 > exe.length) {
            throw new IllegalArgumentException("osclient.exe has no version resource");
        }
        int fileVersionMs = ByteBuffer.wrap(exe, at + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return (fileVersionMs >>> 16) + "." + (fileVersionMs & 0xffff);
    }

    private static String sha256(byte[] exe) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exe));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
