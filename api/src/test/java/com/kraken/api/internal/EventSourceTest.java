package com.kraken.api.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.GameState;
import com.kraken.api.Skill;
import com.kraken.api.actor.NPC;
import com.kraken.api.actor.Player;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.VarbitChanged;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import com.kraken.api.item.Item;
import com.kraken.api.mapping.Mapping;
import com.kraken.api.memory.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The events over the {@link GameFixture}: what each tracker posts when the game changes between
 * polls, the replay of the current state when tracking starts as the API attaches, and the order within a poll.
 */
class EventSourceTest {

    private GameFixture game;
    private Client client;
    private EventBus bus;
    private EventSource source;
    private final List<Object> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        bus = new EventBus();
        source = new EventSource(client, bus);
    }

    private <T> void record(Class<T> type) {
        bus.register(type, seen::add, 0);
    }

    /**
     * Subscribes after the first poll, so tracking starts with a silent baseline rather than a replay.
     *
     * @param types the events to record
     */
    private void recordLate(Class<?>... types) {
        source.poll();
        for (Class<?> type : types) {
            record(type);
        }
        source.poll();
        seen.clear();
    }

    private <T> List<T> seen(Class<T> type) {
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Reads through to the fixture, but once armed steps the cycle counter right after its next read: the client
     * incrementing it just after a read that a {@code clientCycle} raise woke.
     */
    private final class LateIncrement implements Memory {

        private boolean armed;

        @Override
        public byte[] read(long address, int length) {
            byte[] bytes = game.memory.read(address, length);
            long counter = GameFixture.MODULE_BASE + game.map.global("cycle");
            if (armed && address == counter) {
                armed = false;
                game.memory.putInt(counter, game.memory.readInt(counter) + 1);
            }
            return bytes;
        }

        @Override
        public long moduleBase() {
            return game.memory.moduleBase();
        }

        @Override
        public long moduleSize() {
            return game.memory.moduleSize();
        }

        @Override
        public boolean isValid() {
            return game.memory.isValid();
        }
    }

    @Test
    void trackingThatStartsAtAttachReplaysTheCurrentState() {
        record(GameStateChanged.class);
        record(NpcSpawned.class);
        record(PlayerSpawned.class);
        record(StatChanged.class);
        record(ItemContainerChanged.class);
        source.poll();

        assertEquals(List.of(GameState.LOGGED_IN), seen(GameStateChanged.class).stream()
                .map(GameStateChanged::getGameState).toList());
        assertEquals(List.of(5, 6), seen(NpcSpawned.class).stream().map(e -> e.getNpc().getIndex()).toList());
        assertSame(client.getNpcs().get(0), seen(NpcSpawned.class).get(0).getNpc(), "the client's own handle");
        assertEquals(List.of(7, 9, 10), seen(PlayerSpawned.class).stream().map(e -> e.getPlayer().getId()).toList());
        assertEquals(Skill.values().length, seen(StatChanged.class).size());
        assertEquals(new StatChanged(Skill.HITPOINTS, 1_154, 10, 12), seen(StatChanged.class).get(3));
        assertEquals(List.of(93, 94, 95), seen(ItemContainerChanged.class).stream()
                .map(ItemContainerChanged::getContainerId).toList());

        seen.clear();
        source.poll();
        assertEquals(List.of(), seen, "nothing changed since");
    }

    @Test
    void trackingThatStartsLaterTakesASilentBaseline() {
        source.poll();
        record(NpcSpawned.class);
        record(GameStateChanged.class);
        source.poll();
        source.poll();
        assertEquals(List.of(), seen);
    }

    @Test
    void varsAndTheCanvasAreNotReplayed() {
        record(VarbitChanged.class);
        record(CanvasSizeChanged.class);
        source.poll();
        assertEquals(List.of(), seen);
    }

    @Test
    void everyRawGameStateChangeIsPosted() {
        recordLate(GameStateChanged.class);
        for (int state : new int[]{45, 25, 30, 25, 30, 10, 20, 25, 30}) {
            game.setGameState(state);
            source.poll();
        }
        assertEquals(List.of(GameState.HOPPING, GameState.LOADING, GameState.LOGGED_IN, GameState.LOADING,
                        GameState.LOGGED_IN, GameState.LOGIN_SCREEN, GameState.LOGGING_IN, GameState.LOADING,
                        GameState.LOGGED_IN),
                seen(GameStateChanged.class).stream().map(GameStateChanged::getGameState).toList());
    }

    @Test
    void aLoadBetweenTwoPollsPostsLoadingThenLoggedIn() {
        recordLate(GameStateChanged.class);
        game.setMapBase(BASE_X + 8, BASE_Z);
        source.poll();
        source.poll();
        assertEquals(List.of(GameState.LOADING, GameState.LOGGED_IN),
                seen(GameStateChanged.class).stream().map(GameStateChanged::getGameState).toList());
    }

    @Test
    void aDespawnedNpcKeepsWhereItWasAndWhatItWas() {
        recordLate(NpcSpawned.class, NpcDespawned.class);
        NPC goblin = client.getNpcs().get(0);
        game.setNpcLoaded(5, false);
        source.poll();

        List<NpcDespawned> gone = seen(NpcDespawned.class);
        assertEquals(1, gone.size());
        assertSame(goblin, gone.get(0).getNpc());
        assertEquals("Goblin", goblin.getName());
        assertEquals(3029, goblin.getId());
        assertEquals(new WorldPoint(3222 + BASE_X, 3218 + BASE_Z, 0), goblin.getWorldLocation());

        seen.clear();
        game.setNpcLoaded(5, true);
        source.poll();
        assertEquals(List.of(new NpcSpawned(goblin)), seen, "the same NPC back keeps its handle");
    }

    @Test
    void theTickRefreshKeepsWhatADespawnedNpcRemembersCurrent() {
        recordLate(NpcDespawned.class);
        NPC goblin = client.getNpcs().get(0);
        game.setActorTile(GameFixture.NPC, 3230, 3219);
        game.setTick(124);
        source.poll();
        game.setNpcLoaded(5, false);
        source.poll();
        assertEquals(List.of(new NpcDespawned(goblin)), seen);
        assertEquals(new WorldPoint(3230 + BASE_X, 3219 + BASE_Z, 0), goblin.getWorldLocation());
    }

    @Test
    void aNewNpcInAnOldOnesIndexDespawnsTheOldHandle() {
        recordLate(NpcSpawned.class, NpcDespawned.class);
        NPC old = client.getNpcs().get(0);
        game.setNpcSpawnCycle(5, 2000);
        source.poll();
        assertEquals(List.of(), seen, "the index list didn't change, so the pins wait for the tick");
        game.setTick(124);
        source.poll();
        assertEquals(2, seen.size());
        assertEquals(new NpcDespawned(old), seen.get(0));
        NPC fresh = ((NpcSpawned) seen.get(1)).getNpc();
        assertNotSame(old, fresh);
        assertEquals(2000, fresh.getSpawnCycle());
    }

    @Test
    void playersSpawnAndDespawnFromTheClientsList() {
        recordLate(PlayerSpawned.class, PlayerDespawned.class);
        Player bob = client.getTopLevelWorldView().players().byIndex(9);
        game.setPlayersLoaded(7, 10);
        source.poll();
        assertEquals(List.of(new PlayerDespawned(bob)), seen);
        assertEquals("Bob", bob.getName());

        seen.clear();
        game.setPlayersLoaded(7, 9, 10);
        source.poll();
        assertEquals(List.of(new PlayerSpawned(bob)), seen);
    }

    @Test
    void statChangedCarriesTheRealAndBoostedLevels() {
        recordLate(StatChanged.class);
        game.setStat(Skill.RUNECRAFT.ordinal(), 2_000, 15, 14);
        source.poll();
        assertEquals(List.of(new StatChanged(Skill.RUNECRAFT, 2_000, 14, 15)), seen);
    }

    @Test
    void everySkillFiresWhenTheStatsComeBack() {
        recordLate(StatChanged.class);
        game.setStatsSent(false);
        source.poll();
        assertEquals(List.of(), seen);
        game.setStatsSent(true);
        source.poll();
        assertEquals(Skill.values().length, seen.size());
    }

    @Test
    void varbitsChangeBeforeTheirVarpAndFollowTheClientsCopy() {
        recordLate(VarbitChanged.class);
        game.setVarpBits(10, 0x35); // was 0x64: varbit 0 (bits 0-3) 4 -> 5, varbit 1 (bits 4-7) 6 -> 3
        source.poll();
        assertEquals(List.of("10/0=5", "10/1=3", "10/-1=53"), seen(VarbitChanged.class).stream()
                .map(e -> e.getVarpId() + "/" + e.getVarbitId() + "=" + e.getValue()).toList());
        assertEquals(53, client.getVarpValue(10), "the value a handler reads back");

        seen.clear();
        game.setVarp(10, 0x135); // only bit 8 changes: no varbit on varp 10 covers it
        source.poll();
        assertEquals(List.of("10/-1=309"), seen(VarbitChanged.class).stream()
                .map(e -> e.getVarpId() + "/" + e.getVarbitId() + "=" + e.getValue()).toList());
    }

    @Test
    void itemContainerChangedCarriesTheWholeContainer() {
        recordLate(ItemContainerChanged.class);
        game.setInvSlot(InventoryID.INV, 5, 1511, 1);
        source.poll();
        List<ItemContainerChanged> changed = seen(ItemContainerChanged.class);
        assertEquals(1, changed.size());
        assertEquals(InventoryID.INV, changed.get(0).getContainerId());
        Item[] items = changed.get(0).getItemContainer().getItems();
        assertArrayEquals(new Item[]{new Item(1511, 1), new Item(995, 100), new Item(-1, 0), new Item(995, 50),
                new Item(-1, 0), new Item(1511, 1)}, items);
        assertEquals(2, changed.get(0).getItemContainer().count(1511));
    }

    @Test
    void canvasSizeChangedFollowsTheClientAndTheWindow() {
        recordLate(CanvasSizeChanged.class);
        game.setClientSize(1000, 700, 1500, 1050);
        source.poll();
        source.poll();
        game.setClientSize(1000, 700, 2000, 1400);
        source.poll();
        assertEquals(2, seen.size());
    }

    @Test
    void gameTickComesAfterTheTicksOtherEventsAndClientTickLast() {
        recordLate(NpcDespawned.class, StatChanged.class, GameTick.class, ClientTick.class);
        game.setCycle(GameFixture.CYCLE + 1);
        assertFalse(source.poll(), "no tick");
        assertEquals(List.of(ClientTick.class), seen.stream().map(Object::getClass).toList());

        seen.clear();
        game.setNpcLoaded(6, false);
        game.setStat(0, 13_100_000, 99, 99);
        game.setTick(124);
        game.setCycle(GameFixture.CYCLE + 2);
        assertTrue(source.poll());
        assertEquals(List.of(StatChanged.class, NpcDespawned.class, GameTick.class, ClientTick.class),
                seen.stream().map(Object::getClass).toList());
    }

    @Test
    void gameTickFollowsTheTickCounterNotThePolls() {
        recordLate(GameTick.class);
        source.poll();
        source.poll();
        assertEquals(List.of(), seen, "polls without a new tick post no GameTick");

        game.setTick(124);
        source.poll();
        source.poll();
        assertEquals(1, seen.size(), "one GameTick per tick, however many polls see it");
    }

    @Test
    void everyTickBetweenTwoPollsPostsAGameTick() {
        recordLate(GameTick.class);
        game.setTick(126);
        assertTrue(source.poll());
        assertEquals(3, seen.size(), "123 -> 126 is three ticks");

        seen.clear();
        game.setTick(126 + 100);
        source.poll();
        assertEquals(10, seen.size(), "a long stall posts ten, not a burst");
    }

    @Test
    void theLogoutResetOfTheTickCounterPostsNoGameTick() {
        recordLate(GameTick.class);
        game.setTick(0);
        assertFalse(source.poll(), "logout sets the counter to 0: not a tick");
        assertEquals(List.of(), seen);

        game.setTick(1);
        assertTrue(source.poll(), "the first tick after login");
        assertEquals(1, seen.size());
    }

    @Test
    void aFailedTickReadPostsNoGameTick() {
        recordLate(GameTick.class);
        long clientSlot = GameFixture.MODULE_BASE + game.map.global("client");
        game.memory.putLong(clientSlot, 0);
        source.poll();
        game.memory.putLong(clientSlot, GameFixture.CLIENT);
        source.poll();
        assertEquals(List.of(), seen, "a read that failed is not a reset to 0, so nothing moved");
    }

    @Test
    void aClientCycleRaiseWaitsForTheStepItAnnounced() {
        LateIncrement memory = new LateIncrement();
        EventSource hooked = new EventSource(new Client(memory, game.map), bus);
        record(ClientTick.class);
        hooked.poll();

        memory.armed = true;
        hooked.poll();
        assertEquals(List.of(), seen, "without a raise the poll takes the read it got, before the step");
        hooked.poll();
        assertEquals(1, seen.size(), "and posts the step on the next poll");

        seen.clear();
        memory.armed = true;
        hooked.onNative(new NativeEvent(NativeEvent.CLIENT_CYCLE, 1000, new long[4], Map.of(), 1));
        hooked.poll();
        assertEquals(1, seen.size(), "the raise makes the poll wait for the step, so it posts it now");
    }

    @Test
    void aClientCycleRaiseWhoseStepAlreadyCountedPostsNothingMore() {
        record(ClientTick.class);
        source.poll();
        game.setCycle(GameFixture.CYCLE + 1);
        source.poll();
        assertEquals(1, seen.size(), "a poll counted the step before its raise arrived");

        source.onNative(new NativeEvent(NativeEvent.CLIENT_CYCLE, 1000, new long[4], Map.of(), 1));
        source.poll();
        assertEquals(1, seen.size(), "the late raise gives up its wait and posts no second ClientTick");
    }

    @Test
    void clientTickFollowsTheClientsCycleCounter() {
        recordLate(ClientTick.class);
        source.poll();
        assertEquals(List.of(), seen, "no cycle passed, so no ClientTick");

        game.setCycle(GameFixture.CYCLE + 1);
        source.poll();
        assertEquals(1, seen.size());

        seen.clear();
        game.setCycle(GameFixture.CYCLE + 4);
        source.poll();
        assertEquals(3, seen.size(), "one per cycle since the previous poll");

        seen.clear();
        game.setCycle(GameFixture.CYCLE + 4 + 500);
        source.poll();
        assertEquals(50, seen.size(), "a long gap posts a second's worth, not a burst");

        seen.clear();
        game.setCycle(7);
        source.poll();
        assertEquals(1, seen.size(), "a counter that went back is a reset: one cycle");
    }

    @Test
    void theFirstPollTakesTheCycleCounterSilently() {
        record(ClientTick.class);
        source.poll();
        assertEquals(List.of(), seen);
    }

    @Test
    void aMappingWithoutTheCycleCounterPostsOneClientTickPerPoll(@TempDir Path dir) throws IOException {
        JsonObject json;
        try (InputStreamReader in = new InputStreamReader(Mapping.class.getClassLoader()
                .getResourceAsStream(Mapping.RESOURCE_DIR + game.map.version() + ".json"), StandardCharsets.UTF_8)) {
            json = new Gson().fromJson(in, JsonObject.class);
        }
        json.getAsJsonObject("globals").remove("cycle");
        Path file = dir.resolve(game.map.version() + ".json");
        Files.writeString(file, json.toString());
        EventSource older = new EventSource(new Client(game.memory, Mapping.load(file)), bus);

        record(ClientTick.class);
        older.poll();
        older.poll();
        assertEquals(2, seen.size());
    }

    @Test
    void unsubscribingForgetsTheBaseline() {
        var subscriber = bus.register(StatChanged.class, seen::add, 0);
        source.poll();
        bus.unregister(subscriber);
        source.poll();
        game.setStat(0, 13_100_000, 99, 99);
        bus.register(StatChanged.class, seen::add, 0);
        seen.clear();
        source.poll();
        source.poll();
        assertEquals(List.of(), seen, "changes while nobody listened aren't posted");
    }
}
