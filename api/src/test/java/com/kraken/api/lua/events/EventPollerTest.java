package com.kraken.api.lua.events;

import com.kraken.api.GameFixture;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.Graphics;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.events.Events.GameStateTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.kraken.api.lua.ClientState.GAME_STATE_HOPPING;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOADING;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOGGED_IN;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOGGING_IN;
import static com.kraken.api.lua.ClientState.GAME_STATE_TITLE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the {@link EventPoller} over the {@link GameFixture}, changing the game between polls.
 */
class EventPollerTest {

    private GameFixture game;
    private Events events;
    private EventPoller poller;
    private ClientOp client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        events = new Events();
        client = new ClientOp(game.memory, game.map);
        poller = new EventPoller(events, client, new ClientState(game.memory, game.map),
                new Graphics(game.memory, game.map), new Scene(client));
    }

    @Test
    void firstPollIsASilentBaseline() {
        List<Object> seen = new ArrayList<>();
        events.subscribe(Events.ON_STAT_TRANSMIT, seen::add);
        events.subscribe(Events.ON_VAR_TRANSMIT, seen::add);
        events.subscribe(Events.ON_INV_TRANSMIT, seen::add);
        events.subscribe(Events.ON_GAME_STATE_CHANGED, seen::add);
        events.subscribe(Events.ON_NPC_LOAD, (name, index) -> seen.add(name));
        events.subscribe(Events.ON_GAME_TICK, () -> seen.add("tick"));
        poller.poll();
        poller.poll();
        assertTrue(seen.isEmpty(), "nothing changed, so nothing fires: " + seen);
    }

    @Test
    void tickFiresWhenTheCounterMoves() {
        int[] ticks = {0};
        events.subscribe(Events.ON_GAME_TICK, () -> ticks[0]++);
        poller.poll();
        game.setTick(124);
        poller.poll();
        poller.poll();
        assertEquals(1, ticks[0]);
    }

    @Test
    void gameStateTriggersFollowTheClient() {
        List<GameStateTrigger> seen = new ArrayList<>();
        events.subscribe(Events.ON_GAME_STATE_CHANGED, seen::add);
        poller.poll(); // baseline: logged in

        step(GAME_STATE_HOPPING);
        assertEquals(List.of(GameStateTrigger.LEAVE_WORLD_STATE, GameStateTrigger.ENTER_WORLD_HOP_STATE), seen);
        seen.clear();

        step(GAME_STATE_LOADING);
        step(GAME_STATE_LOGGED_IN);
        assertEquals(List.of(GameStateTrigger.ENTER_WORLD_STATE), seen);
        seen.clear();

        step(GAME_STATE_LOADING);
        step(GAME_STATE_LOGGED_IN);
        assertEquals(List.of(), seen, "a map reload inside the world isn't a trigger");

        step(GAME_STATE_TITLE);
        assertEquals(List.of(GameStateTrigger.LEAVE_WORLD_STATE, GameStateTrigger.ENTER_TITLE_STATE), seen);
        seen.clear();

        step(GAME_STATE_LOGGING_IN);
        step(GAME_STATE_LOADING);
        step(GAME_STATE_LOGGED_IN);
        assertEquals(List.of(GameStateTrigger.ENTER_LOADING_STATE, GameStateTrigger.ENTER_WORLD_STATE), seen);
    }

    @Test
    void statTransmitListsChangedStats() {
        List<List<StatUpdate>> seen = new ArrayList<>();
        events.subscribe(Events.ON_STAT_TRANSMIT, seen::add);
        poller.poll();

        game.setStat(3, 1_400, 11, 11);
        poller.poll();
        assertEquals(List.of(List.of(new StatUpdate(3, 1_400, 11, 11))), seen);
    }

    @Test
    void everyStatFiresWhenStatsComeBackAfterLogout() {
        List<List<StatUpdate>> seen = new ArrayList<>();
        events.subscribe(Events.ON_STAT_TRANSMIT, seen::add);
        poller.poll();
        game.setStatsSent(false);
        poller.poll();
        game.setStatsSent(true);
        poller.poll();
        assertEquals(1, seen.size());
        assertEquals(game.map.offset("Client.skillIndexMax"), seen.get(0).size());
    }

    @Test
    void varTransmitListsChangedVarpIds() {
        List<int[]> seen = new ArrayList<>();
        events.subscribe(Events.ON_VAR_TRANSMIT, seen::add);
        poller.poll();
        game.setVarp(2, 1);
        game.setVarp(40, 1);
        poller.poll();
        assertEquals(1, seen.size());
        assertArrayEquals(new int[]{2, 40}, seen.get(0));
    }

    @Test
    void invTransmitListsChangedSlots() {
        List<List<Inventory>> seen = new ArrayList<>();
        events.subscribe(Events.ON_INV_TRANSMIT, seen::add);
        poller.poll();

        game.setInvSlot(ClientOp.INV_BACKPACK, 0, -1, 0);
        game.setInvSlot(ClientOp.INV_BACKPACK, 5, 1511, 1);
        poller.poll();
        // Slot 4 is new but empty, which reads the same as not being held, so it isn't reported
        assertEquals(List.of(List.of(new Inventory(ClientOp.INV_BACKPACK, List.of(
                new InvItem(-1, 0, 0), new InvItem(1511, 5, 1))))), seen);
    }

    @Test
    void npcLoadAndUnloadFollowTheIndexList() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_NPC_LOAD, (name, index) -> seen.add("load " + name + " " + index));
        events.subscribe(Events.ON_NPC_UNLOAD, (name, index) -> seen.add("unload " + name + " " + index));
        poller.poll();

        game.memory.putInt(GameFixture.WORLD + game.map.offset("World.npcIndexCount"), 0);
        poller.poll();
        game.memory.putInt(GameFixture.WORLD + game.map.offset("World.npcIndexCount"), 1);
        poller.poll();
        // NPC 6 (a multi-NPC currently showing as a Goblin) leaves too; only index 5 is first in the list
        assertEquals(List.of("unload Goblin 5", "unload Goblin 6", "load Goblin 5"), seen);
    }

    @Test
    void varbitUpdatesFireForEachChangedVarbitBeforeTheVarpTransmit() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_VARBIT_UPDATE, (id, value) -> seen.add("varbit " + id + " = " + value));
        events.subscribe(Events.ON_VAR_TRANSMIT, ids -> seen.add("varps " + java.util.Arrays.toString(ids)));
        poller.poll();

        game.setVarp(10, 0x35); // was 0x64: varbit 0 (bits 0-3) 4 -> 5, varbit 1 (bits 4-7) 6 -> 3
        poller.poll();
        assertEquals(List.of("varbit 0 = 5", "varbit 1 = 3", "varps [10]"), seen);

        seen.clear();
        game.setVarp(10, 0x135); // only bit 8 changes: no varbit on varp 10 covers it
        poller.poll();
        assertEquals(List.of("varps [10]"), seen);
    }

    @Test
    void varbitUpdatesFollowTheServersVarps() {
        List<Integer> seen = new ArrayList<>();
        events.subscribe(Events.ON_VARBIT_UPDATE, (id, value) -> seen.add(id));
        poller.poll();
        game.setVarpBits(10, 0); // a client-side change: the client doesn't fire for these
        poller.poll();
        assertTrue(seen.isEmpty());
    }

    @Test
    void unsubscribingForgetsTheBaseline() {
        List<int[]> seen = new ArrayList<>();
        int id = events.subscribe(Events.ON_VAR_TRANSMIT, seen::add);
        poller.poll();
        events.unsubscribe(id);
        game.setVarp(2, 1);
        poller.poll();
        events.subscribe(Events.ON_VAR_TRANSMIT, seen::add);
        poller.poll();
        assertTrue(seen.isEmpty(), "changes while nobody listened aren't replayed");
    }

    @Test
    void subscribingTheSameCallbackTwiceReturnsTheSameId() {
        GameTickCallback callback = () -> {
        };
        assertEquals(events.subscribe(Events.ON_GAME_TICK, callback), events.subscribe(Events.ON_GAME_TICK, callback));
    }

    @Test
    void screenResizeFiresWhenTheClientChangesSize() {
        int[] resizes = {0};
        events.subscribe(Events.ON_SCREEN_RESIZE, () -> resizes[0]++);
        poller.poll();
        poller.poll();
        assertEquals(0, resizes[0]);
        game.setClientSize(1000, 700, 1500, 1050);
        poller.poll();
        poller.poll();
        assertEquals(1, resizes[0]);
        game.setClientSize(1000, 700, 2000, 1400);
        poller.poll();
        assertEquals(2, resizes[0], "the window alone changing size (a display scaling change) counts too");
    }

    @Test
    void mapRebuildFiresAroundTheLoadingState() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_MAP_REBUILD_PRE, () -> seen.add("pre"));
        events.subscribe(Events.ON_MAP_REBUILD_POST, () -> seen.add("post"));
        poller.poll();
        step(GAME_STATE_LOADING);
        assertEquals(List.of("pre"), seen);
        game.setMapBase(GameFixture.BASE_X + 8, GameFixture.BASE_Z);
        step(GAME_STATE_LOADING);
        step(GAME_STATE_LOGGED_IN);
        assertEquals(List.of("pre", "post"), seen);
    }

    @Test
    void aLoginLoadOnlyFiresPost() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_MAP_REBUILD_PRE, () -> seen.add("pre"));
        events.subscribe(Events.ON_MAP_REBUILD_POST, () -> seen.add("post"));
        game.setGameState(GAME_STATE_TITLE);
        poller.poll();
        step(GAME_STATE_LOGGING_IN);
        step(GAME_STATE_LOADING);
        step(GAME_STATE_LOGGED_IN);
        assertEquals(List.of("post"), seen);
    }

    @Test
    void aRebuildBetweenPollsIsCaughtByTheBaseMoving() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_MAP_REBUILD_PRE, () -> seen.add("pre"));
        events.subscribe(Events.ON_MAP_REBUILD_POST, () -> seen.add("post"));
        poller.poll();
        game.setMapBase(GameFixture.BASE_X, GameFixture.BASE_Z + 8);
        poller.poll();
        poller.poll();
        assertEquals(List.of("pre", "post"), seen);
    }

    @Test
    void locChangesFireOncePerTickWithTheLocActive() {
        List<String> seen = new ArrayList<>();
        events.subscribe(Events.ON_LOC_UNLOAD, (id, name, layer) -> seen.add("unload " + id + " " + name + " "
                + layer + " " + client.locCoord()));
        events.subscribe(Events.ON_LOC_LOAD, (id, name, layer) -> seen.add("load " + id + " " + name + " " + layer
                + " " + client.locCoord()));
        poller.poll();
        game.setWall(0, 1, 1, GameFixture.WALL_LOC);
        poller.poll();
        assertTrue(seen.isEmpty(), "scans wait for the next tick");
        game.setTick(124);
        poller.poll();
        assertEquals(List.of("unload 1500 Door 0 (1, 1, 0)", "load 1700 Wall 0 (1, 1, 0)"), seen);
        seen.clear();
        game.setWall(0, 1, 1, -1);
        game.setTick(125);
        poller.poll();
        assertEquals(List.of("unload 1700 Wall 0 (1, 1, 0)"), seen);
    }

    @Test
    void aMapRebuildRebaselinesTheLocs() {
        List<Integer> seen = new ArrayList<>();
        events.subscribe(Events.ON_LOC_LOAD, (id, name, layer) -> seen.add(id));
        poller.poll();
        game.setWall(0, 2, 2, GameFixture.WALL_LOC);
        game.setMapBase(GameFixture.BASE_X + 8, GameFixture.BASE_Z);
        game.setTick(124);
        poller.poll();
        assertTrue(seen.isEmpty(), "a new area's locs are the map load's, not loc changes");
    }

    @Test
    void eventIdsMatchTheClientsEnum() {
        assertEquals(List.of(), Events.verify(game.map));
    }

    private void step(int gameState) {
        game.setGameState(gameState);
        poller.poll();
    }
}
