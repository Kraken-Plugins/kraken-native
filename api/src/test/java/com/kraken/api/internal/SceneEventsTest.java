package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.NPC;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.DecorativeObjectSpawned;
import com.kraken.api.events.GameObjectDespawned;
import com.kraken.api.events.GameObjectSpawned;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GroundObjectSpawned;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemDespawned;
import com.kraken.api.events.ItemQuantityChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.ProjectileSpawned;
import com.kraken.api.events.TileObjectDespawned;
import com.kraken.api.events.TileObjectSpawned;
import com.kraken.api.events.WallObjectDespawned;
import com.kraken.api.events.WallObjectSpawned;
import com.kraken.api.internal.event.EventSource;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.WallObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.DECOR_LOC;
import static com.kraken.api.GameFixture.DOOR_LOC;
import static com.kraken.api.GameFixture.LAMP_LOC;
import static com.kraken.api.GameFixture.TREE_LOC;
import static com.kraken.api.GameFixture.WALL_LOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Object and ground item spawns over the {@link GameFixture}, with the scene-load behaviour (the whole new scene
 * spawns, the old one doesn't despawn, {@code LOGGED_IN} comes after the spawns), loc and pile changes between ticks,
 * {@link InteractingChanged} and {@link ProjectileMoved}.
 */
class SceneEventsTest {

    private static final int OPEN_DOOR = 1501;

    private GameFixture game;
    private Client client;
    private EventBus bus;
    private EventSource source;
    private final List<Object> seen = new ArrayList<>();
    private int tick = 123;

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

    /**
     * Subscribes after the first poll, so tracking starts with a silent baseline rather than a replay.
     *
     * @param types the events to record
     */
    private void recordLate(Class<?>... types) {
        source.poll();
        record(types);
        source.poll();
        seen.clear();
    }

    private void nextTick() {
        game.setTick(++tick);
        source.poll();
    }

    private <T> List<T> seen(Class<T> type) {
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * What was seen, as short names in order: the event's class, and the game state or the object's id.
     *
     * @return one entry per event
     */
    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (Object event : seen) {
            String name = event.getClass().getSimpleName();
            if (event instanceof GameStateChanged changed) {
                name += " " + changed.getGameState();
            } else if (event instanceof TileObjectSpawned spawned) {
                name += " " + spawned.getTileObject().getId();
            } else if (event instanceof TileObjectDespawned despawned) {
                name += " " + despawned.getTileObject().getId();
            } else if (event instanceof ItemSpawned spawned) {
                name += " " + spawned.getItem().getId();
            }
            names.add(name);
        }
        return names;
    }

    @Test
    void attachReplaysTheSceneBeforeLoggedIn() {
        record(GameStateChanged.class, WallObjectSpawned.class, DecorativeObjectSpawned.class,
                GameObjectSpawned.class, GroundObjectSpawned.class, ItemSpawned.class, NpcSpawned.class);
        source.poll();
        assertEquals(List.of("WallObjectSpawned " + DOOR_LOC, "GroundObjectSpawned " + DECOR_LOC,
                "WallObjectSpawned " + WALL_LOC, "GameObjectSpawned " + TREE_LOC,
                "DecorativeObjectSpawned " + LAMP_LOC, "ItemSpawned 995", "ItemSpawned 1511", "ItemSpawned 995",
                "GameStateChanged LOGGED_IN", "NpcSpawned", "NpcSpawned"), names());

        WallObjectSpawned door = seen(WallObjectSpawned.class).get(0);
        assertEquals(new WorldPoint(BASE_X + 1, BASE_Z + 1, 0), door.getTile().getWorldLocation());
        assertEquals(door.getWallObject(), client.getScene().getTile(0, 1, 1).getWallObject());

        seen.clear();
        source.poll();
        game.setTick(124);
        source.poll();
        assertEquals(List.of(), seen, "nothing changed since");
    }

    @Test
    void oneHandlerTakesAllFourKinds() {
        record(TileObjectSpawned.class);
        source.poll();
        assertEquals(5, seen.size());
    }

    @Test
    void lateTrackingIsSilentUntilTheNextLoad() {
        recordLate(TileObjectSpawned.class, ItemSpawned.class);
        nextTick();
        assertEquals(List.of(), seen);
    }

    @Test
    void aSceneLoadSpawnsTheNewSceneAndDespawnsNothing() {
        recordLate(GameStateChanged.class, TileObjectSpawned.class, TileObjectDespawned.class, ItemSpawned.class,
                ItemDespawned.class);
        game.setGameState(25);
        source.poll();
        game.setWall(0, 1, 1, OPEN_DOOR);
        source.poll();
        assertEquals(List.of("GameStateChanged LOADING"), names(), "nothing is read while loading");

        seen.clear();
        game.setGameState(30);
        source.poll();
        List<String> names = names();
        assertEquals(9, names.size(), "five objects, three items, logged in");
        assertEquals("WallObjectSpawned " + OPEN_DOOR, names.get(0));
        assertEquals("GameStateChanged LOGGED_IN", names.get(8), "logged in comes after the scene's spawns");
        assertTrue(seen(TileObjectDespawned.class).isEmpty());
        assertTrue(seen(ItemDespawned.class).isEmpty());
    }

    @Test
    void aLoadBetweenTwoPollsStillSpawnsTheNewScene() {
        recordLate(GameStateChanged.class, TileObjectSpawned.class, ItemSpawned.class);
        game.setMapBase(BASE_X + 8, BASE_Z);
        source.poll();
        List<String> names = names();
        assertEquals("GameStateChanged LOADING", names.get(0));
        assertEquals("GameStateChanged LOGGED_IN", names.get(names.size() - 1));
        assertEquals(10, names.size());
        WallObjectSpawned door = seen(WallObjectSpawned.class).get(0);
        assertEquals(new WorldPoint(BASE_X + 8 + 1, BASE_Z + 1, 0), door.getWallObject().getWorldLocation(),
                "the new scene's base");
    }

    @Test
    void aLostConnectionKeepsTheScene() {
        recordLate(GameStateChanged.class, TileObjectSpawned.class, ItemSpawned.class);
        game.setGameState(40);
        source.poll();
        game.setGameState(30);
        source.poll();
        assertEquals(List.of("GameStateChanged CONNECTION_LOST", "GameStateChanged LOGGED_IN"), names());
    }

    @Test
    void aLoginSpawnsTheScene() {
        game.setGameState(10);
        record(TileObjectSpawned.class);
        source.poll();
        assertEquals(List.of(), seen, "no scene at the login screen");
        game.setGameState(30);
        source.poll();
        assertEquals(5, seen.size());
    }

    @Test
    void aDoorOpeningDespawnsTheOldWallThenSpawnsTheNew() {
        record(WallObjectSpawned.class, WallObjectDespawned.class);
        source.poll();
        WallObject closed = seen(WallObjectSpawned.class).get(0).getWallObject();
        seen.clear();

        game.setWall(0, 1, 1, OPEN_DOOR);
        source.poll();
        assertEquals(List.of(), seen, "locs are compared once per tick");
        nextTick();
        assertEquals(List.of("WallObjectDespawned " + DOOR_LOC, "WallObjectSpawned " + OPEN_DOOR), names());
        assertEquals(closed, seen(WallObjectDespawned.class).get(0).getWallObject(), "equal to the spawned one");
    }

    @Test
    void aFelledTreeDespawns() {
        recordLate(GameObjectDespawned.class);
        game.removeGameObjects(0, 5, 5);
        nextTick();
        assertEquals(TREE_LOC, seen(GameObjectDespawned.class).get(0).getGameObject().getId());
    }

    @Test
    void itemsKeepTheirHandleThroughQuantityChangesAndDespawns() {
        record(ItemSpawned.class, ItemDespawned.class, ItemQuantityChanged.class);
        source.poll();
        List<TileItem> spawned = seen(ItemSpawned.class).stream().map(ItemSpawned::getItem).toList();
        seen.clear();

        game.setPileCount(0, 3, 3, 2, 8);
        nextTick();
        ItemQuantityChanged changed = seen(ItemQuantityChanged.class).get(0);
        assertSame(spawned.get(2), changed.getItem());
        assertEquals(5, changed.getOldQuantity());
        assertEquals(8, changed.getNewQuantity());
        assertEquals(8, spawned.get(2).getQuantity());
        assertEquals(new WorldPoint(BASE_X + 3, BASE_Z + 3, 0), changed.getTile().getWorldLocation());

        seen.clear();
        game.removeFirstPileItem(0, 3, 3);
        nextTick();
        assertEquals(List.of(new ItemDespawned(changed.getTile(), spawned.get(0))), seen,
                "the other coins and the logs stay");
    }

    @Test
    void aDropSpawnsAndIsSeenThePollAfterTheTick() {
        recordLate(ItemSpawned.class, ItemDespawned.class);
        nextTick();
        game.setPile(0, 6, 2, new int[]{4151}, new int[]{1});
        source.poll();
        assertEquals(List.of("ItemSpawned 4151"), names(), "the poll after a tick looks again");
        seen.clear();
        game.setPile(0, 6, 2, new int[0], new int[0]);
        source.poll();
        assertEquals(List.of(), seen, "not again until the next tick");
        nextTick();
        assertEquals(1, seen(ItemDespawned.class).size());
    }

    @Test
    void interactingIsReplayedThenComparedEachTick() {
        record(NpcSpawned.class, InteractingChanged.class);
        source.poll();
        List<InteractingChanged> changes = seen(InteractingChanged.class);
        assertEquals(1, changes.size());
        NPC goblin = client.getNpcs().get(0);
        assertSame(goblin, changes.get(0).getSource());
        assertSame(client.getLocalPlayer(), changes.get(0).getTarget());
        assertTrue(seen.indexOf(changes.get(0)) > seen.indexOf(seen(NpcSpawned.class).get(1)), "after the spawns");

        seen.clear();
        game.setInteracting(GameFixture.NPC, 0, -1);
        source.poll();
        assertEquals(List.of(), seen, "targets are compared on ticks");
        nextTick();
        assertEquals(List.of(new InteractingChanged(goblin, null)), seen);
        seen.clear();
        nextTick();
        assertEquals(List.of(), seen);
    }

    @Test
    void projectilesMovePostOnceWhenTheyAppear() {
        game.setProjectilesInFlight();
        record(ProjectileSpawned.class);
        record(ProjectileMoved.class);
        source.poll();
        game.setProjectilesInFlight(0);
        source.poll();
        source.poll();
        List<ProjectileMoved> moved = seen(ProjectileMoved.class);
        assertEquals(1, moved.size());
        ProjectileMoved spell = moved.get(0);
        assertEquals(GameFixture.SPELL_PROJECTILE, spell.getProjectile().getId());
        assertEquals(2, seen.size(), "a spawn, then the move");
        assertSame(spell.getProjectile(), ((ProjectileSpawned) seen.get(0)).getProjectile());
        NPC goblin = client.getTopLevelWorldView().npcs().byIndex(5);
        assertEquals(goblin.getLocalLocation(), spell.getPosition(), "its target actor's location");
        LocalPoint at = spell.getPosition();
        assertEquals(client.getTopLevelWorldView().getTileHeight(at.getX(), at.getY(), 0) - 31, spell.getZ());

        seen.clear();
        game.setProjectilesInFlight(0, 1);
        source.poll();
        ProjectileMoved arrow = seen(ProjectileMoved.class).get(0);
        assertEquals(new LocalPoint(6 * 128 + 64, 6 * 128 + 64, -1), arrow.getPosition(), "its target tile's centre");
        assertEquals(-36, arrow.getZ());
    }

    @Test
    void projectilesInFlightAtAttachAreNotReplayed() {
        record(ProjectileMoved.class);
        source.poll();
        source.poll();
        assertEquals(List.of(), seen);
        game.setProjectilesInFlight();
        source.poll();
        game.setProjectilesInFlight(1);
        source.poll();
        assertEquals(1, seen.size(), "the arrow came back: a new one");
        assertNull(((ProjectileMoved) seen.get(0)).getProjectile().getSourceActor());
    }
}
