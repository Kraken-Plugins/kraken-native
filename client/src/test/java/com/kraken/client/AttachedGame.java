package com.kraken.client;

import com.kraken.api.GameSession;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * A stand-in for the attached game for tests: a session over the bundled 241.3 mapping and a memory image that reads
 * zeros, so it reports no game state and its poll loop never starts.
 */
public final class AttachedGame {

    private AttachedGame() {
    }

    /**
     * Builds the session over the zeroed memory.
     *
     * @param bus where the session posts its events
     * @return the session
     */
    public static GameSession session(EventBus bus) {
        Memory zeros = new Memory() {
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
        };
        try {
            return new GameSession(zeros, Objects.requireNonNull(Mapping.bundled("241.3"), "bundled 241.3 mapping"),
                    bus);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
