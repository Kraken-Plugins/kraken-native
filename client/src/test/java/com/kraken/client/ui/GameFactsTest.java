package com.kraken.client.ui;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.WorldType;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Kraken page shows about the game: the facts read, and their words.
 */
class GameFactsTest {

    @Test
    void stateReadsAsWords() {
        assertEquals("Logged in", facts(GameState.LOGGED_IN, Set.of()).stateText());
        assertEquals("Login screen", facts(GameState.LOGIN_SCREEN, Set.of()).stateText());
        assertEquals("Connection lost", facts(GameState.CONNECTION_LOST, Set.of()).stateText());
    }

    @Test
    void worldTypesStartWithMembersOrFreeToPlay() {
        assertEquals("Free-to-play", facts(GameState.LOGGED_IN, Set.of()).typesText());
        assertEquals("Members", facts(GameState.LOGGED_IN, EnumSet.of(WorldType.MEMBERS)).typesText());
        assertEquals("Members, PvP, High risk", facts(GameState.LOGGED_IN,
                EnumSet.of(WorldType.HIGH_RISK, WorldType.PVP, WorldType.MEMBERS)).typesText());
        assertEquals("Free-to-play, Fresh start", facts(GameState.LOGGED_IN,
                EnumSet.of(WorldType.FRESH_START_WORLD)).typesText());
        assertNull(facts(GameState.LOGIN_SCREEN, Set.of()).typesText(), "no world while logged out");
    }

    @Test
    void jagexAccountNeedsTheSessionCharacterAndName() {
        Map<String, String> env = Map.of("JX_SESSION_ID", "secret", "JX_CHARACTER_ID", "123",
                "JX_DISPLAY_NAME", " Zezima ");
        assertEquals("Zezima", GameFacts.jagexAccount(env));
        assertNull(GameFacts.jagexAccount(Map.of()));
        assertNull(GameFacts.jagexAccount(Map.of("JX_CHARACTER_ID", "123", "JX_DISPLAY_NAME", "Zezima")),
                "no session");
        assertNull(GameFacts.jagexAccount(Map.of("JX_SESSION_ID", "secret", "JX_CHARACTER_ID", "123",
                "JX_DISPLAY_NAME", "")), "no display name");
    }

    @Test
    void anEmptyGameHasNoPlayerOrWorld() throws IOException {
        GameFacts read = GameFacts.read(new Client(new Zeros(), Mapping.bundled("241.3")));
        assertNotEquals(GameState.LOGGED_IN, read.state());
        assertNull(read.player());
        assertEquals(-1, read.world());
        assertTrue(read.types().isEmpty());
    }

    private static GameFacts facts(GameState state, Set<WorldType> types) {
        return new GameFacts(state, null, -1, types);
    }

    /**
     * A process image that reads zeros: a client with no game loaded.
     */
    private static final class Zeros implements Memory {
        @Override
        public byte[] read(long address, int length) {
            return new byte[length];
        }

        @Override
        public long moduleBase() {
            return 0;
        }

        @Override
        public long moduleSize() {
            return 0;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
