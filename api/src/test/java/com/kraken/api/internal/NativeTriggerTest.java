package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.NPC;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.WallObjectSpawned;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.internal.event.NativeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.DOOR_LOC;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The native-event triggers: {@link EventSource#onNative} drives the trackers to read and post without waiting on a
 * game tick, so an event a slowed timer poll would notice late is posted at the next poll instead. The trackers still
 * produce the objects, so the push path posts exactly what the poll path does.
 */
class NativeTriggerTest {

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

    private void record(Class<?>... types) {
        for (Class<?> type : types) {
            bus.register(type, seen::add, 0);
        }
    }

    private <T> List<T> seen(Class<T> type) {
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static NativeEvent locLoad(int packed) {
        return new NativeEvent("ON_LOC_LOAD", 40, new long[4], Map.of(NativeEvent.RDX, packed), 1);
    }

    private static NativeEvent function(String name) {
        return new NativeEvent(name, 1000, new long[4], Map.of(), 1);
    }

    @Test
    void aLocLoadTriggerReadsJustThatTileWithoutATick() {
        record(WallObjectSpawned.class);
        source.poll();
        seen.clear();

        game.setWall(0, 2, 2, DOOR_LOC);
        source.poll();
        assertEquals(List.of(), seen, "locs aren't read on a non-tick poll");

        source.onNative(locLoad(2 << 14 | 2)); // plane 0, scene tile (2, 2)
        source.poll();
        List<WallObjectSpawned> spawns = seen(WallObjectSpawned.class);
        assertEquals(1, spawns.size());
        assertEquals(new WorldPoint(BASE_X + 2, BASE_Z + 2, 0), spawns.get(0).getTile().getWorldLocation());
        assertEquals(DOOR_LOC, spawns.get(0).getWallObject().getId());

        seen.clear();
        game.setTick(124);
        source.poll();
        assertEquals(List.of(), seen, "the targeted read folded the loc into the baseline, so the tick doesn't repost");
    }

    @Test
    void aLocLoadTriggerDecodesAWorldCoord() {
        record(WallObjectSpawned.class);
        source.poll();
        seen.clear();

        game.setWall(0, 2, 2, DOOR_LOC);
        source.onNative(locLoad((BASE_X + 2) << 14 | (BASE_Z + 2))); // the same tile as a world coord
        source.poll();
        assertEquals(1, seen(WallObjectSpawned.class).size(), "a world coord is read with the base subtracted");
    }

    @Test
    void anObjAddTriggerReadsThePilesWithoutATick() {
        record(ItemSpawned.class);
        source.poll();
        source.poll(); // clear the after-tick window the replay opened
        seen.clear();

        game.setPile(0, 6, 2, new int[]{4151}, new int[]{1});
        source.poll();
        assertEquals(List.of(), seen, "ground items aren't read on a plain non-tick poll");

        source.onNative(function(NativeEvent.OBJ_ADD));
        source.poll();
        List<ItemSpawned> spawns = seen(ItemSpawned.class);
        assertEquals(1, spawns.size());
        assertEquals(4151, spawns.get(0).getItem().getId());
    }

    @Test
    void anInfoDecodeTriggerComparesTargetsWithoutATick() {
        record(InteractingChanged.class);
        source.poll();
        source.poll();
        seen.clear();
        NPC goblin = client.getNpcs().get(0);

        game.setInteracting(GameFixture.NPC, 0, -1);
        source.poll();
        assertEquals(List.of(), seen, "targets aren't compared on a plain non-tick poll");

        source.onNative(function(NativeEvent.NPC_INFO_DECODE));
        source.poll();
        assertEquals(List.of(new InteractingChanged(goblin, null)), seen);
    }

    @Test
    void anUnhandledNativeNameIsIgnored() {
        record(StatChanged.class);
        source.poll();
        seen.clear();

        source.onNative(new NativeEvent("ON_DIALOG_ABORT", 32, new long[4], Map.of(), 1));
        source.onNative(new NativeEvent("ON_GAME_TICK", 44, new long[4], Map.of(), 2));
        source.poll();
        assertEquals(List.of(), seen, "no tracker handles these, and GameTick still comes from the tick counter");
    }

    @Test
    void aTransmitTriggerPostsOnlyWhatChanged() {
        record(StatChanged.class);
        source.poll();
        seen.clear();

        source.onNative(function("ON_STAT_TRANSMIT"));
        source.poll();
        assertEquals(List.of(), seen, "nothing changed, so the routed trigger posts nothing");

        game.setStat(0, 14_000_000, 99, 99);
        source.onNative(function("ON_STAT_TRANSMIT"));
        source.poll();
        assertEquals(1, seen(StatChanged.class).size(), "the stat the transmit changed");
    }
}
