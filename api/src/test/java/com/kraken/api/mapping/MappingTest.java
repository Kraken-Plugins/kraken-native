package com.kraken.api.mapping;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.kraken.api.TestSupport.SEED_SHA256;
import static com.kraken.api.TestSupport.SEED_VERSION;
import static com.kraken.api.TestSupport.seedMapping;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void readsEventHookSites() throws IOException {
        Mapping map = seedMapping();
        assertEquals(List.of(new Mapping.EventHook(0xc44d0L, 0, -1, 1)), map.eventHooks("ON_GAME_TICK"));
        assertEquals(map.function("dispatchEvent"), map.eventHooks("ON_GAME_TICK").get(0).fn());
        Mapping.EventHook locLoad = map.eventHooks("ON_LOC_LOAD").get(0);
        assertTrue(locLoad.idFixed());
        assertEquals(map.enumValue("events", "ON_LOC_LOAD", -1), locLoad.id());
        assertEquals(4, locLoad.args());
        assertEquals(2, map.eventHooks("ON_FRIEND_TRANSMIT").size()); // a generic and a dedicated raiser
        assertFalse(map.hasEventHooks("ON_MAP_REBUILD_PRE")); // no native raise site found in this build
        assertEquals(List.of(), map.eventHooks("NO_SUCH_EVENT"));
    }

    @Test
    void missingKeysThrowOrFallBack() throws IOException {
        Mapping map = seedMapping();
        assertThrows(Mapping.MissingMappingException.class, () -> map.offset("Actor.noSuchField"));
        assertThrows(Mapping.MissingMappingException.class, () -> map.global("noSuchGlobal"));
        assertEquals(0x718, map.offsetOr("Player.name", 0x718));
    }
}
