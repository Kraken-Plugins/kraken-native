package com.kraken.client.worldhopper;

import com.kraken.api.World;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldService}: keeping the last list, one fetch at a time, and telling listeners after each fetch.
 */
class WorldServiceTest {

    /**
     * A world client whose fetches the test completes.
     */
    private static final class FakeClient extends WorldClient {
        final List<CompletableFuture<List<World>>> fetches = new ArrayList<>();

        @Override
        public CompletableFuture<List<World>> fetch() {
            CompletableFuture<List<World>> fetch = new CompletableFuture<>();
            fetches.add(fetch);
            return fetch;
        }
    }

    private static final List<World> WORLDS = List.of(World.builder().id(301).build(),
            World.builder().id(302).build());

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    void keepsTheFetchedListAndFindsWorldsInIt() throws Exception {
        FakeClient fake = new FakeClient();
        WorldService service = new WorldService(fake, null);
        AtomicInteger told = new AtomicInteger();
        service.addListener(told::incrementAndGet);
        assertEquals(List.of(), service.getWorlds());
        assertNull(service.getUpdatedAt());

        service.refresh();
        assertTrue(service.isFetching());
        service.refresh();
        assertEquals(1, fake.fetches.size(), "one fetch at a time");

        fake.fetches.get(0).complete(WORLDS);
        drainEdt();
        assertFalse(service.isFetching());
        assertSame(WORLDS, service.getWorlds());
        assertNotNull(service.getUpdatedAt());
        assertNull(service.getError());
        assertEquals(302, service.findWorld(302).getId());
        assertNull(service.findWorld(999));
        assertEquals(1, told.get());
    }

    @Test
    void aFailedFetchKeepsTheLastListAndSaysWhy() throws Exception {
        FakeClient fake = new FakeClient();
        WorldService service = new WorldService(fake, null);
        service.refresh();
        fake.fetches.get(0).complete(WORLDS);

        service.refresh();
        fake.fetches.get(1).completeExceptionally(new IOException("timed out"));
        drainEdt();
        assertSame(WORLDS, service.getWorlds());
        assertEquals("timed out", service.getError());
        assertFalse(service.isFetching());

        service.refresh();
        assertEquals(3, fake.fetches.size(), "a failed fetch doesn't block the next");
    }
}
