package com.kraken.api.internal.action;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.action.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Toggle}: a sent state stands in for the var's until the var changes or two ticks pass, so a second call
 * inside the lag doesn't click again.
 */
class ToggleTest {

    private static final int VARP = 20;

    private GameFixture game;
    private Toggle toggle;
    private int clicks;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        Client client = new Client(game.memory, game.map);
        toggle = new Toggle(client, () -> client.getVarpValue(VARP), value -> value == 1);
        game.setTick(100);
        game.setVarp(VARP, 0);
    }

    private ActionResult click() {
        clicks++;
        return ActionResult.SENT;
    }

    @Test
    void aStateAlreadyHeldSendsNothing() {
        assertEquals(ActionResult.SENT, toggle.set(false, this::click));
        assertEquals(0, clicks);
    }

    @Test
    void aSecondCallInsideTheLagDoesNotClickAgain() {
        assertEquals(ActionResult.SENT, toggle.set(true, this::click));
        assertTrue(toggle.isOn(), "taken as on while the answer is due");
        assertEquals(ActionResult.SENT, toggle.set(true, this::click));
        assertEquals(1, clicks);
    }

    @Test
    void theVarChangingEndsTheLag() {
        toggle.set(true, this::click);
        game.setVarp(VARP, 1);
        assertTrue(toggle.isOn());
        game.setVarp(VARP, 0);
        assertFalse(toggle.isOn(), "the var is the state again");
        toggle.set(true, this::click);
        assertEquals(2, clicks);
    }

    @Test
    void twoTicksEndTheLag() {
        toggle.set(true, this::click);
        game.setTick(101);
        assertTrue(toggle.isOn());
        game.setTick(102);
        assertFalse(toggle.isOn(), "the server didn't take it");
    }

    @Test
    void aClickThatWasNotSentIsNotRemembered() {
        assertEquals(ActionResult.NOT_CONNECTED, toggle.set(true, () -> ActionResult.NOT_CONNECTED));
        assertFalse(toggle.isOn());
    }
}
