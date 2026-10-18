package com.kraken.api;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientBuildTest {

    /** A fake exe: padding, then a VS_FIXEDFILEINFO header for version major.minor.build.private. */
    private static byte[] exeWithVersion(int major, int minor, int build, int priv) {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(101); // deliberately unaligned
        buf.putInt(0xFEEF04BD).putInt(0x00010000).putInt(major << 16 | minor).putInt(build << 16 | priv);
        return buf.array();
    }

    @Test
    void readsMajorMinorFromTheVersionResource() {
        ClientBuild build = ClientBuild.of(exeWithVersion(241, 3, 0, 0));
        assertEquals("241.3", build.version());
        assertEquals(64, build.sha256().length());
    }

    @Test
    void ignoresBuildAndPrivateParts() {
        assertEquals("241.3", ClientBuild.version(exeWithVersion(241, 3, 7, 1)));
    }

    @Test
    void rejectsAnExeWithoutAVersionResource() {
        assertThrows(IllegalArgumentException.class, () -> ClientBuild.version(new byte[512]));
    }
}
