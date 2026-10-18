package com.kraken.api.mapping;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.kraken.api.TestSupport.SEED_SHA256;
import static com.kraken.api.TestSupport.SEED_VERSION;
import static com.kraken.api.TestSupport.seedMapping;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reads the committed seed mapping through every accessor. */
class MappingTest {

    @Test
    void identifiesItsBuild() throws IOException {
        Mapping map = seedMapping();
        assertEquals(SEED_VERSION, map.version());
        assertEquals(SEED_SHA256, map.sha256());
    }

    @Test
    void readsGlobalsFieldsAndContainers() throws IOException {
        Mapping map = seedMapping();
        assertEquals(0xe98c18L, map.global("client"));
        assertEquals(0x3f0, map.offset("Actor.pathX"));
        assertEquals(0x895c8, map.offset("Client.camera.pos")); // field names may contain dots
        assertEquals(0x10, map.container("hash_map.node.value"));
    }

    @Test
    void missingKeysThrowOrFallBack() throws IOException {
        Mapping map = seedMapping();
        assertThrows(Mapping.MissingMappingException.class, () -> map.offset("Actor.noSuchField"));
        assertThrows(Mapping.MissingMappingException.class, () -> map.global("noSuchGlobal"));
        assertEquals(0x718, map.offsetOr("Player.name", 0x718));
    }
}
