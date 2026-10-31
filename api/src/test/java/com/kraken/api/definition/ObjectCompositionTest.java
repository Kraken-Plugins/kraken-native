package com.kraken.api.definition;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.ObjectDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ObjectComposition#getImpostor()} over the {@link GameFixture}'s vars and locs: the varbit or varp picks the
 * shown loc, values out of range take the last id, and -1 shows nothing.
 */
class ObjectCompositionTest {

    /**
     * Opcode 77 on varbit 2 (varp 11 bit 0): 0 shows the door, 1 the oak, and the trailing -1 everything else.
     */
    private static final int VARBIT_MULTI = 1900;

    /**
     * Opcode 92 on varp 20: 0 shows the door, 1 shows nothing, and the fallback (the lamp) everything else.
     */
    private static final int VARP_MULTI = 1901;

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        game.putConfigFile(ConfigTypes.LOC, VARBIT_MULTI, new ConfigFile().op(2).str("Gate")
                .op(77).g2(2).g2(0xffff).g1(1).g2(GameFixture.DOOR_LOC).g2(GameFixture.TREE_LOC).end());
        game.putConfigFile(ConfigTypes.LOC, VARP_MULTI, new ConfigFile().op(2).str("Lever")
                .op(92).g2(0xffff).g2(20).g2(GameFixture.LAMP_LOC).g1(1).g2(GameFixture.DOOR_LOC).g2(0xffff).end());
        client = new Client(game.memory, game.map);
    }

    @Test
    void varbitPicksTheShownLoc() {
        ObjectComposition gate = client.getObjectDefinition(VARBIT_MULTI);
        game.setVarp(11, 0);
        assertEquals(GameFixture.DOOR_LOC, gate.getImpostor().getId());
        assertEquals("Door", gate.getImpostor().getName());
        game.setVarp(11, 1);
        assertEquals(GameFixture.TREE_LOC, gate.getImpostor().getId(), "the pick follows the varbit on each call");
        assertEquals("Chop down", gate.getImpostor().getActions()[0]);
    }

    @Test
    void varpPicksTheShownLocAndOutOfRangeTakesTheLast() {
        ObjectComposition lever = client.getObjectDefinition(VARP_MULTI);
        game.setVarp(20, 0);
        assertEquals(GameFixture.DOOR_LOC, lever.getImpostor().getId());
        game.setVarp(20, 1);
        assertNull(lever.getImpostor(), "a -1 child shows nothing");
        game.setVarp(20, 2);
        assertEquals(GameFixture.LAMP_LOC, lever.getImpostor().getId(), "past the end: the fallback");
        game.setVarp(20, -5);
        assertEquals(GameFixture.LAMP_LOC, lever.getImpostor().getId(), "below 0: the fallback");
    }

    @Test
    void varpIsReadFromTheCopyVarbitsRead() {
        ObjectComposition lever = client.getObjectDefinition(VARP_MULTI);
        game.setVarp(20, 0);
        game.setVarpBits(20, 2);
        assertEquals(GameFixture.LAMP_LOC, lever.getImpostor().getId());
    }

    @Test
    void opcode77EndsInNothing() {
        game.putConfigFile(14, 2, ConfigFile.varbit(11, 0, 3));
        Client wider = new Client(game.memory, game.map);
        game.setVarp(11, 7);
        assertNull(wider.getObjectDefinition(VARBIT_MULTI).getImpostor(), "past the children: the trailing -1");
    }

    @Test
    void aLocThatDoesNotTransformIsItsOwnImpostor() {
        ObjectComposition door = client.getObjectDefinition(GameFixture.DOOR_LOC);
        assertSame(door, door.getImpostor());
    }

    @Test
    void withoutAClientNothingResolves() {
        byte[] lever = new ConfigFile().op(92).g2(0xffff).g2(20).g2(GameFixture.LAMP_LOC).g1(0)
                .g2(GameFixture.DOOR_LOC).end();
        assertNull(ObjectDecoder.decode(VARP_MULTI, lever, false, null).getImpostor());
        ObjectComposition plain = ObjectDecoder.decode(99999, new ConfigFile().op(2).str("Rock").end(), false, null);
        assertSame(plain, plain.getImpostor());
    }
}
