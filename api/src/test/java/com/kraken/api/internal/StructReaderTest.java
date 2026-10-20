package com.kraken.api.internal;

import com.kraken.api.FakeMemory;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.TestSupport.seedMapping;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the EASTL string (SSO + heap) and hash_map decoders over a fake memory image. */
class StructReaderTest {

    private FakeMemory memory;
    private StructReader reader;

    @BeforeEach
    void setUp() throws IOException {
        Mapping mapping = seedMapping();
        memory = new FakeMemory();
        reader = new StructReader(memory, mapping);
    }

    @Test
    void readsSmallString() {
        long str = 0x10_000_000L;
        memory.alloc(str, 0x18);
        memory.putSsoString(str, "Hello");
        assertEquals("Hello", reader.string(str));
    }

    @Test
    void readsHeapString() {
        long str = 0x11_000_000L;
        long text = 0x11_001_000L;
        memory.alloc(str, 0x18);
        memory.alloc(text, 64);
        String value = "a name longer than twenty-three chars";
        memory.putBytes(text, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        memory.putLong(str, text);                 // char* at +0
        memory.putLong(str + 8, value.length());   // size at +8
        memory.putByte(str + 0x17, 0x80);          // heap flag: top bit of byte 23
        assertEquals(value, reader.string(str));
    }

    @Test
    void nullStringIsEmpty() {
        assertEquals("", reader.string(0));
    }

    @Test
    void hashGetFindsValueAndMissesCleanly() {
        long map = 0x12_000_000L;
        long buckets = 0x12_001_000L;
        long node = 0x12_002_000L;
        long value = 0x40_0000_0000L;
        memory.alloc(map, 0x20);
        memory.alloc(buckets, 16); // 2 buckets
        memory.alloc(node, 0x20);
        memory.putLong(map + 0x8, buckets);
        memory.putLong(map + 0x10, 2);
        // key 42 lives in bucket 42 % 2 == 0
        memory.putLong(buckets, node);
        memory.putInt(node, 42);
        memory.putLong(node + 0x10, value);
        memory.putLong(node + 0x18, 0);

        assertEquals(value, reader.hashGet(map, 42));
        assertEquals(0, reader.hashGet(map, 99)); // bucket 1 is empty
        assertEquals(0, reader.hashGet(0, 42));   // null map
    }
}
