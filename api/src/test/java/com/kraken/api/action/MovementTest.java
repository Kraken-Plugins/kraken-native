package com.kraken.api.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.MenuAction;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.VarPlayerID;
import com.kraken.api.internal.invoke.NativeCall;
import com.kraken.api.widgets.WidgetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Movement}: walking to tiles of the loaded scene, and running through the run orb with the lag-aware toggle.
 */
class MovementTest {

    private static final int RUN_ORB = InterfaceID.Orbs.RUNBUTTON;
    private static final int RUN_ORB_NOMAP = InterfaceID.OrbsNomap.RUNBUTTON;

    private GameFixture game;
    private Client client;
    private Movement movement;
    private final List<List<NativeCall>> sent = new ArrayList<>();
    private Actions reference;
    private final List<List<NativeCall>> expected = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
        client.getActions().setInvoker(sent::add);
        client.getInteractions().setClickPolicy(ClickPolicy.NONE);
        movement = client.getMovement();
        reference = new Client(game.memory, game.map).getActions();
        reference.setInvoker(expected::add);
        game.setTick(100);
        game.setVarp(VarPlayerID.OPTION_RUN, 0);
    }

    private void assertSentAsReference() {
        assertEquals(1, sent.size(), "one batch");
        assertEquals(expected.get(expected.size() - 1), sent.get(0), "the batch the matching Actions call sends");
    }

    /**
     * Puts a run orb in the interface store, at its packed id, with one op, and opens its group.
     *
     * @param packed the orb's packed component id
     * @return the orb's address
     */
    private long loadOrb(int packed) {
        int group = packed >>> 16;
        int child = packed & 0xffff;
        long[] components = new long[child + 1];
        for (int i = 0; i < child; i++) {
            components[i] = game.addComponent(group << 16 | i, -1, -1, -1, WidgetType.LAYER, 0, 0, 1, 1);
        }
        components[child] = game.addComponent(packed, -1, -1, -1, WidgetType.GRAPHIC, 0, 0, 30, 30);
        game.setOps(components[child], "Toggle Run");
        game.setGroup(group, components);
        game.openGroup(group, 0, 0);
        return components[child];
    }

    private void hide(long component) {
        game.memory.putByte(component + game.map.offset("IfType.hidden"), 1);
    }

    @Test
    void walkingReachesTheLoadedSceneOnly() {
        WorldPoint near = new WorldPoint(BASE_X + 2, BASE_Z + 3, 0);
        assertEquals(ActionResult.SENT, movement.walk(near));
        reference.walk(near);
        assertSentAsReference();

        sent.clear();
        assertEquals(ActionResult.OUT_OF_SCENE, movement.walk(new WorldPoint(BASE_X + 200, BASE_Z, 0)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aLocalPointWalksToItsTileOrIsOutOfScene() {
        WorldPoint near = new WorldPoint(BASE_X + 2, BASE_Z + 3, 0);
        LocalPoint inside = LocalPoint.fromWorld(client, near);
        assertNotNull(inside);
        assertEquals(ActionResult.SENT, movement.walk(inside));
        reference.walk(near);
        assertSentAsReference();

        sent.clear();
        LocalPoint outside = LocalPoint.fromScene(200, 0, client.getTopLevelWorldView());
        assertEquals(ActionResult.OUT_OF_SCENE, movement.walk(outside));
        assertTrue(sent.isEmpty());
    }

    @Test
    void theDestinationIsTheClientsWalkTarget() {
        assertNull(movement.getDestination(), "no destination set");
        game.setDestination(10, 12);
        assertEquals(LocalPoint.fromScene(10, 12, client.getTopLevelWorldView()), movement.getDestination());
    }

    @Test
    void runningFollowsTheVarpAndClicksTheOrbOnlyWhenItDiffers() {
        loadOrb(RUN_ORB);
        assertFalse(movement.isRunning());
        assertEquals(ActionResult.SENT, movement.setRunning(false));
        assertTrue(sent.isEmpty(), "already not running");

        assertEquals(ActionResult.SENT, movement.setRunning(true));
        reference.menuAction(-1, RUN_ORB, MenuAction.CC_OP, 1);
        assertSentAsReference();
        assertTrue(movement.isRunning(), "taken as running while the server's answer is due");

        sent.clear();
        assertEquals(ActionResult.SENT, movement.setRunning(true));
        assertTrue(sent.isEmpty(), "no second click inside the lag");

        game.setTick(102);
        assertFalse(movement.isRunning(), "the server didn't answer in time");
        assertEquals(ActionResult.SENT, movement.setRunning(true));
        assertSentAsReference();

        game.setVarp(VarPlayerID.OPTION_RUN, 1);
        assertTrue(movement.isRunning());
        sent.clear();
        assertEquals(ActionResult.SENT, movement.setRunning(true));
        assertTrue(sent.isEmpty(), "the varp already says running");
    }

    @Test
    void theRunOrbIsTheFirstShownOfTheTwoLoaded() {
        long hidden = loadOrb(RUN_ORB);
        hide(hidden);
        loadOrb(RUN_ORB_NOMAP);
        assertEquals(ActionResult.SENT, movement.setRunning(true));
        reference.menuAction(-1, RUN_ORB_NOMAP, MenuAction.CC_OP, 1);
        assertSentAsReference();
    }

    @Test
    void aHiddenRunOrbIsUsedWhenNothingElseIsLoaded() {
        hide(loadOrb(RUN_ORB));
        assertEquals(ActionResult.SENT, movement.setRunning(true));
        reference.menuAction(-1, RUN_ORB, MenuAction.CC_OP, 1);
        assertSentAsReference();
    }

    @Test
    void noRunOrbLoadedIsNotFound() {
        assertEquals(ActionResult.NOT_FOUND, movement.setRunning(true));
        assertTrue(sent.isEmpty());
        assertFalse(movement.isRunning(), "the varp still reads");
    }

    @Test
    void withoutTheHookNothingIsSentAndNothingIsRemembered() {
        loadOrb(RUN_ORB);
        client.getActions().setInvoker(null);
        assertEquals(ActionResult.NOT_CONNECTED, movement.setRunning(true));
        assertFalse(movement.isRunning());
    }
}
