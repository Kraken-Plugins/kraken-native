package com.kraken.client;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.ProfileType;
import com.kraken.api.WorldType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link StandInClient} plugins get: logged-out answers before the API attaches, the attached client's after.
 */
class StandInClientTest {

    private final AtomicReference<Client> attached = new AtomicReference<>();
    private final AtomicReference<GameState> detachedState = new AtomicReference<>(GameState.STARTING);
    private final Client client = StandInClient.create(attached::get, detachedState::get);

    @Test
    void beforeAttachItAnswersAsALoggedOutClient() {
        assertEquals(GameState.STARTING, client.getGameState());
        assertEquals(-1, client.getTickCount());
        assertFalse(client.isLoggedIn());
        assertNull(client.getLocalPlayer());
        assertEquals(List.of(), client.getNpcs());
        assertEquals(EnumSet.noneOf(WorldType.class), client.getWorldType());
        assertArrayEquals(new int[0], client.getRealSkillLevels());
        assertEquals("", client.getVarcStrValue(1));
        assertEquals(ProfileType.STANDARD, client.getProfileType());
        assertTrue(client.toString().contains("not attached"));
        assertEquals(client, client);

        detachedState.set(GameState.UNKNOWN);
        assertEquals(GameState.UNKNOWN, client.getGameState(), "after a failed attach");
    }

    @Test
    void onceAttachedItForwardsToTheRealClient() {
        attached.set(fake());
        assertEquals(GameState.LOGGED_IN, client.getGameState());
        assertEquals(42, client.getTickCount());
        assertThrows(IllegalStateException.class, client::getNpcs, "the real client's exceptions come through as they are");
    }

    private static Client fake() {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGameState" -> GameState.LOGGED_IN;
                    case "getTickCount" -> 42;
                    default -> throw new IllegalStateException(method.getName() + " isn't faked");
                });
    }
}
