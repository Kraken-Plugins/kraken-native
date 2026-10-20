package com.kraken.api.lua;

import com.kraken.api.GameFixture;
import com.kraken.api.lua.ClientState.ProfileType;
import com.kraken.api.lua.ClientState.Stats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClientState} over the {@link GameFixture}: each method against what the native handler would return. */
class ClientStateTest {

    private GameFixture game;
    private ClientState state;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        state = new ClientState(game.memory, game.map);
    }

    @Test
    void readsScalars() {
        assertEquals(30, state.getGameState());
        assertTrue(state.isLoggedIn());
        assertEquals(123, state.getTickCount());
    }

    @Test
    void isLoggedInNeedsALoginSessionToo() {
        game.setLoginToken(-1);
        assertFalse(state.isLoggedIn(), "the handler also checks the login token");
        game.setLoginToken(5);
        game.setGameState(ClientState.GAME_STATE_HOPPING);
        assertFalse(state.isLoggedIn());
    }

    @Test
    void readsStats() {
        assertEquals(99, state.getStatEffectiveLevel(Stats.SKILL_ATTACK));
        assertEquals(13_034_431, state.getStatXP(Stats.SKILL_ATTACK));
        assertEquals(12, state.getStatEffectiveLevel(Stats.SKILL_HITPOINTS), "boosted");
        assertEquals(10, state.getStatBaseLevel(Stats.SKILL_HITPOINTS));
        assertEquals(1_154, state.getStatXP(3));
    }

    @Test
    void statsAreMinusOneBeforeTheServerSendsThem() {
        game.setStatsSent(false);
        assertEquals(-1, state.getStatXP(0));
        assertEquals(-1, state.getStatBaseLevel(0));
        assertEquals(-1, state.getStatEffectiveLevel(0));
        assertEquals(-1, state.getStatRemainingXPUntilLevel(0));
        assertNull(state.getStatsAll());
    }

    @Test
    void statIdsOutsideTheArraysAreMinusOne() {
        assertEquals(-1, state.getStatXP(-1));
        assertEquals(-1, state.getStatXP(state.getStatCount()));
    }

    @Test
    void remainingXpFollowsTheClient() {
        assertEquals(1_358 - 1_154, state.getStatRemainingXPUntilLevel(3), "level 10 to 11");
        assertEquals(13_034_431 - 1_154, state.getStatRemainingXPUntilLevel(3, 99));
        assertEquals(0, state.getStatRemainingXPUntilLevel(3, 9), "below the current level");
        assertEquals(0, state.getStatRemainingXPUntilLevel(3, 100), "above 99");
        assertEquals(0, state.getStatRemainingXPUntilLevel(0), "already 99");
        assertEquals(83, state.getStatRemainingXPUntilLevel(Stats.SKILL_COOKING), "level 1 to 2");
    }

    @Test
    void readsAllStatsAtOnce() {
        ClientState.StatArrays all = state.getStatsAll();
        assertNotNull(all);
        assertEquals(state.getStatCount(), all.xp().length);
        assertEquals(1_154, all.xp()[3]);
        assertEquals(12, all.level()[3]);
        assertEquals(10, all.base()[3]);
    }

    @Test
    void readsVarpsWithinTheArray() {
        assertEquals(GameFixture.VARP_COUNT, state.getVarpCount());
        assertEquals(20, state.getVarp(2));
        game.setVarp(2, 7);
        assertEquals(7, state.getVarp(2));
        assertEquals(-1, state.getVarp(GameFixture.VARP_COUNT), "past the end");
        assertEquals(-1, state.getVarp(-1));
        int[] all = state.getVarps();
        assertEquals(GameFixture.VARP_COUNT, all.length);
        assertEquals(630, all[63]);
    }

    @Test
    void decodesProfileTypeLikeTheHandler() {
        assertEquals(ProfileType.STANDARD, state.getProfileType());
        game.setWorldFlags(1 << 16);
        assertEquals(ProfileType.BETA, state.getProfileType());
        game.setWorldFlags(1 << 29 | 1);
        assertEquals(ProfileType.DEADMAN, state.getProfileType());
        game.setWorldFlags(1 << 6);
        assertEquals(ProfileType.PVP_ARENA, state.getProfileType());
        game.setWorldFlags(1 << 8 | 1 << 29);
        assertEquals(ProfileType.QUEST_SPEEDRUNNING, state.getProfileType(), "later checks win");
        game.setWorldFlags(1 << 30 | 1 << 16);
        assertEquals(ProfileType.SEASONAL, state.getProfileType());
    }

    @Test
    void looksUpVarcsByIdAndType() {
        assertTrue(state.hasVarcs());
        assertEquals(42, state.getIntegerVarc(10));
        assertEquals(-5, state.getIntegerVarc(30));
        assertEquals(-1, state.getIntegerVarc(20), "a string varc");
        assertEquals(-1, state.getIntegerVarc(15), "not set");
        assertEquals(-1, state.getIntegerVarc(99), "past the last entry");
        assertEquals("hello", state.getStringVarc(20));
        assertEquals("", state.getStringVarc(10), "an int varc");
        assertEquals("", state.getStringVarc(1));
    }

    @Test
    void listsVarcs() {
        assertEquals(Map.of(10, 42, 30, -5), state.getIntegerVarcs());
        assertEquals(Map.of(20, "hello"), state.getStringVarcs());
    }

    @Test
    void xpTableMatchesTheGame() {
        // Spot checks against the well-known OSRS table
        game.setStat(5, 0, 1, 1);
        assertEquals(83, state.getStatRemainingXPUntilLevel(5, 2));
        assertEquals(101_333, state.getStatRemainingXPUntilLevel(5, 50));
        assertEquals(13_034_431, state.getStatRemainingXPUntilLevel(5, 99));
    }
}
