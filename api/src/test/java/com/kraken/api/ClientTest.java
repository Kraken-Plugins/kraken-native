package com.kraken.api;

import com.kraken.api.actor.NPC;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.NPCComposition;
import com.kraken.api.definition.ObjectComposition;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.internal.ConfigTypes;
import com.kraken.api.internal.ScriptComponents;
import com.kraken.api.internal.ScriptStack;
import com.kraken.api.internal.VarcValues;
import com.kraken.api.item.EquipmentInventorySlot;
import com.kraken.api.scene.WorldView;
import com.kraken.api.widgets.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Client} over the {@link GameFixture}: game state and login, world and profile types, the entity lists
 * and by-name lookups, skills and experience, varps and varcs, and the definitions of items, NPCs and objects.
 */
class ClientTest {

    /**
     * The stat id of the Hitpoints skill in the fixture (the client's stats array index).
     */
    private static final int HITPOINTS = Skill.HITPOINTS.ordinal();

    /**
     * The game state the client reports while hopping worlds.
     */
    private static final int GAME_STATE_HOPPING = 45;

    private GameFixture game;
    private Client client;

    /**
     * Lays out the fixture and a client over it.
     *
     * @throws IOException if the bundled mapping can't be read
     */
    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    @Test
    void theDestinationIsTheFlaggedSceneTile() {
        assertNull(client.getLocalDestinationLocation(), "(0, 0) is no destination");
        game.setDestination(20, 31);
        LocalPoint destination = client.getLocalDestinationLocation();
        assertEquals(new LocalPoint(20 * 128 + 64, 31 * 128 + 64, WorldView.TOPLEVEL), destination);
        assertEquals(new WorldPoint(GameFixture.BASE_X + 20, GameFixture.BASE_Z + 31, 0),
                WorldPoint.fromLocal(client, destination));
        game.setDestination(0, 0);
        assertNull(client.getLocalDestinationLocation(), "cleared once the player arrives");
    }

    @Test
    void gameStateMapsTheRawValue() {
        assertEquals(GameState.LOGGED_IN, client.getGameState());
        assertEquals(30, client.rawGameState());
        game.setGameState(25);
        assertEquals(GameState.LOADING, client.getGameState());
        game.setGameState(7);
        assertEquals(GameState.UNKNOWN, client.getGameState(), "a value the enum doesn't know");
        for (GameState state : GameState.values()) {
            assertEquals(state, GameState.of(state.getState()));
        }
    }

    @Test
    void readsTicksCyclesAndLogin() {
        assertEquals(123, client.getTickCount());
        assertEquals(GameFixture.CYCLE, client.getGameCycle());
        assertTrue(client.isLoggedIn());
        game.setLoginToken(-1);
        assertFalse(client.isLoggedIn(), "the client also checks the login token");
    }

    @Test
    void isLoggedInNeedsALoginSessionToo() {
        game.setLoginToken(5);
        game.setGameState(GAME_STATE_HOPPING);
        assertFalse(client.isLoggedIn());
    }

    @Test
    void worldTypesAreTheWorldFlagBits() {
        assertEquals(EnumSet.noneOf(WorldType.class), client.getWorldType());
        assertEquals(ProfileType.STANDARD, client.getProfileType());
        game.setWorldFlags(1 | 1 << 3 | 1 << 29);
        assertEquals(EnumSet.of(WorldType.MEMBERS, WorldType.DEADMAN), client.getWorldType(), "bit 3 has no type");
        assertEquals(ProfileType.DEADMAN, client.getProfileType());
        assertTrue(WorldType.isPvpWorld(client.getWorldType()));
        assertEquals(1 | 1 << 29, WorldType.toMask(client.getWorldType()));
    }

    @Test
    void decodesProfileTypeFromTheWorldFlags() {
        assertEquals(ProfileType.STANDARD, client.getProfileType());
        game.setWorldFlags(1 << 16);
        assertEquals(ProfileType.BETA, client.getProfileType());
        game.setWorldFlags(1 << 29 | 1);
        assertEquals(ProfileType.DEADMAN, client.getProfileType());
        game.setWorldFlags(1 << 6);
        assertEquals(ProfileType.PVP_ARENA, client.getProfileType());
        game.setWorldFlags(1 << 8 | 1 << 29);
        assertEquals(ProfileType.QUEST_SPEEDRUNNING, client.getProfileType(), "later checks win");
        game.setWorldFlags(1 << 30 | 1 << 16);
        assertEquals(ProfileType.SEASONAL, client.getProfileType());
    }

    @Test
    void listsTheLoadedEntities() {
        assertArrayEquals(new int[]{5, 6}, client.npcIndices());
        assertArrayEquals(new int[]{7, 9, 10}, client.playerIndices());
        assertEquals(7, client.getLocalPlayer().getId());
        assertEquals(List.of(5, 6), client.getNpcs().stream().map(NPC::getIndex).toList());
    }

    @Test
    void findsNpcsByExactName() {
        assertEquals(5, client.getNpcIndexByName("Goblin"));
        assertArrayEquals(new int[]{5, 6}, client.getNpcIndicesByName("Goblin"));
        assertArrayEquals(new int[0], client.getNpcIndicesByName("goblin"), "case-sensitive");
        assertEquals(-1, client.getNpcIndexByName("Man"));
        game.setVarp(11, 111);
        assertArrayEquals(new int[]{6}, client.getNpcIndicesByName("Goblin chief"));
    }

    @Test
    void findsPlayersByNormalisedName() {
        assertEquals(7, client.getPlayerIndexByName("ZEZIMA"));
        assertEquals(9, client.getPlayerIndexByName("Bob"));
        assertEquals(-1, client.getPlayerIndexByName("Nobody"));
    }

    @Test
    void unknownIndicesAreSafe() {
        assertNull(client.npc(999));
        assertNull(client.player(999));
        assertEquals(-1, client.getNpcIndexByName("Nobody"));
    }

    @Test
    void skillsAreIndexedByTheClientsStatId() {
        assertEquals(99, client.getRealSkillLevel(Skill.ATTACK));
        assertEquals(10, client.getRealSkillLevel(Skill.HITPOINTS));
        assertEquals(12, client.getBoostedSkillLevel(Skill.HITPOINTS));
        assertEquals(1_154, client.getSkillExperience(Skill.HITPOINTS));
        assertEquals(13_034_431, client.getSkillExperience(Skill.ATTACK));
        game.setStat(Skill.RUNECRAFT.ordinal(), 2_000, 15, 14);
        assertEquals(14, client.getRealSkillLevel(Skill.RUNECRAFT));
        assertEquals(14, client.getRealSkillLevels()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(15, client.getBoostedSkillLevels()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(2_000, client.getSkillExperiences()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(99 + 10 + 14 + Skill.values().length - 3, client.getTotalLevel(), "every other skill is 1");
    }

    @Test
    void skillNamesAndInventoryIdsMatchTheClient() {
        assertEquals("Runecraft", Skill.RUNECRAFT.getName());
        assertEquals("Hitpoints", Skill.HITPOINTS.getName());
        assertEquals(3, EquipmentInventorySlot.WEAPON.getSlotIdx());
        assertEquals(93, InventoryID.INV);
        assertEquals(94, InventoryID.WORN);
        assertEquals(95, InventoryID.BANK);
    }

    @Test
    void readsAllStatsAtOnce() {
        Client.Stats all = client.stats();
        assertNotNull(all);
        assertEquals(client.getRealSkillLevels().length, all.xp().length);
        assertEquals(1_154, all.xp()[HITPOINTS]);
        assertEquals(12, all.level()[HITPOINTS], "boosted");
        assertEquals(10, all.base()[HITPOINTS], "real");
    }

    @Test
    void skillsReadMinusOneBeforeTheServerSendsThem() {
        game.setStatsSent(false);
        assertEquals(-1, client.getRealSkillLevel(Skill.ATTACK));
        assertEquals(-1, client.getBoostedSkillLevel(Skill.ATTACK));
        assertEquals(-1, client.getSkillExperience(Skill.ATTACK));
        assertEquals(-1, client.getTotalLevel());
        int[] levels = client.getRealSkillLevels();
        assertTrue(levels.length >= Skill.values().length);
        assertTrue(Arrays.stream(levels).allMatch(level -> level == -1));
    }

    @Test
    void experienceTableMatchesTheGame() {
        // Spot checks against the well-known OSRS table; the remaining XP to a level is this minus the current XP
        assertEquals(83, Experience.getXpForLevel(2));
        assertEquals(1_358, Experience.getXpForLevel(11));
        assertEquals(101_333, Experience.getXpForLevel(50));
        assertEquals(13_034_431, Experience.getXpForLevel(99));
        assertEquals(1_358 - 1_154, Experience.getXpForLevel(11) - client.getSkillExperience(Skill.HITPOINTS),
                "level 10 to 11");
        assertEquals(13_034_431 - 1_154, Experience.getXpForLevel(99) - client.getSkillExperience(Skill.HITPOINTS));
    }

    @Test
    void varpsReadTheClientsCopyAndTheServersOnRequest() {
        assertEquals(100, client.getServerVarpValue(10));
        game.setVarpBits(10, 0xf);
        assertEquals(0xf, client.getVarpValue(10), "a change the client made locally, read by getVarpValue");
        assertEquals(100, client.getServerVarpValue(10));
        assertEquals(0xf, client.getVarps()[10]);
        assertEquals(100, client.getServerVarps()[10]);
        assertEquals(GameFixture.VARP_COUNT, client.getVarps().length);
        assertEquals(-1, client.getVarpValue(GameFixture.VARP_COUNT), "outside the varp array");
        assertEquals(-1, client.getVarpValue(-1));
        assertEquals(0xf, client.getVarbitValue(0), "varbit 0 is bits 0-3 of varp 10");
    }

    @Test
    void serverVarpsReadWithinTheArray() {
        assertEquals(GameFixture.VARP_COUNT, client.getServerVarps().length);
        assertEquals(20, client.getServerVarpValue(2));
        game.setVarp(2, 7);
        assertEquals(7, client.getServerVarpValue(2));
        assertEquals(-1, client.getServerVarpValue(GameFixture.VARP_COUNT), "past the end");
        assertEquals(-1, client.getServerVarpValue(-1));
        assertEquals(630, client.getServerVarps()[63]);
    }

    @Test
    void varcsReadZeroWhenUnset() {
        assertEquals(42, client.getVarcIntValue(10));
        assertEquals(-5, client.getVarcIntValue(30));
        assertEquals(0, client.getVarcIntValue(11));
        assertEquals("hello", client.getVarcStrValue(20));
        assertEquals("", client.getVarcStrValue(21));
    }

    @Test
    void intVarcsReadZeroForAStringVarcAndPastTheLastEntry() {
        assertEquals(0, client.getVarcIntValue(20), "a string varc");
        assertEquals(0, client.getVarcIntValue(15), "not set");
        assertEquals(0, client.getVarcIntValue(99), "past the last entry");
        assertEquals("", client.getVarcStrValue(10), "an int varc");
        assertEquals("", client.getVarcStrValue(1));
    }

    @Test
    void listsVarcs() {
        assertEquals(Map.of(10, 42, 30, -5, 20, "hello"), client.getVarcMap());
    }

    @Test
    void readsEveryIntAndStringVarcInOneGo() {
        game.setVarcs(Map.of(10, 42, 20, "hello", 30, -5, 40, GameFixture.LONG_TEXT));
        VarcValues varcs = client.readVarcs();
        assertEquals(Map.of(10, 42, 30, -5), varcs.ints());
        assertEquals(Map.of(20, "hello", 40, GameFixture.LONG_TEXT), varcs.strings());

        game.setVarcs(Map.of());
        assertEquals(new VarcValues(Map.of(), Map.of()), client.readVarcs(), "an empty store");
    }

    @Test
    void aVarcReadThatFailsPartWayGivesNothing() {
        game.setVarcs(Map.of(10, 42, 40, GameFixture.LONG_TEXT));
        game.memory.putLong(GameFixture.VARC_ENTRIES + game.map.offset("VarcEntry.size")
                + game.map.offset("VarcEntry.value"), 0xdead0000L);
        assertNull(client.readVarcs(), "a string whose text can't be read");

        game.memory.putLong(GameFixture.CLIENT + game.map.offset("Client.varcs"), 0xdead0000L);
        assertNull(client.readVarcs(), "a store that can't be read");
    }

    @Test
    void readsTheScriptStacksOffTheRunner() {
        assertEquals(3, client.getIntStackSize());
        assertArrayEquals(new int[]{10, 20, 30}, client.getIntStack());
        assertEquals(3, client.getObjectStackSize());
        assertArrayEquals(new Object[]{"Lumbridge", 7, 1L << 40}, client.getObjectStack());

        game.setIntStack();
        game.setObjectStack("Varrock");
        assertArrayEquals(new int[0], client.getIntStack(), "an emptied stack");
        assertArrayEquals(new Object[]{"Varrock"}, client.getObjectStack());
    }

    @Test
    void theStackGettersGiveACapturedStackOnlyOnTheThreadThatSetIt() throws Exception {
        client.setScriptStack(new ScriptStack(new int[]{0, 5}, new Object[]{"Falador"}));
        assertArrayEquals(new int[]{0, 5}, client.getIntStack());
        assertEquals(2, client.getIntStackSize());
        assertArrayEquals(new Object[]{"Falador"}, client.getObjectStack());
        assertEquals(1, client.getObjectStackSize());

        int[] elsewhere = new int[1];
        Thread other = new Thread(() -> elsewhere[0] = client.getIntStackSize());
        other.start();
        other.join();
        assertEquals(3, elsewhere[0], "another thread reads the game");

        client.getIntStack()[0] = 99;
        assertArrayEquals(new int[]{0, 5}, client.getIntStack(), "each call gives a copy");
        client.setScriptStack(null);
        assertArrayEquals(new int[]{10, 20, 30}, client.getIntStack(), "read from the game again");
    }

    @Test
    void theScriptWidgetsAreTheRunnersComponentsOrTheCapturedOnes() {
        assertNull(client.getScriptActiveWidget(), "between scripts, none");
        assertNull(client.getScriptDotWidget());

        game.setScriptComponents(game.inventoryItem(), 0);
        Widget active = client.getScriptActiveWidget();
        assertNotNull(active);
        assertEquals(149 << 16, active.getId());
        assertEquals(0, active.getIndex(), "the item slot the runner points at");
        assertNull(client.getScriptDotWidget());

        client.setScriptComponents(new ScriptComponents(-1, -1, 162 << 16 | 1, -1));
        assertNull(client.getScriptActiveWidget(), "a captured event's components win");
        assertEquals(162 << 16 | 1, client.getScriptDotWidget().getId());
        client.setScriptComponents(null);
        assertNotNull(client.getScriptActiveWidget(), "read from the game again");
    }

    @Test
    void itemDefinitionsExposeTheirFields() {
        ItemComposition logs = client.getItemDefinition(1511);
        assertEquals(1511, logs.getId());
        assertEquals("Logs", logs.getName());
        assertEquals(4, logs.getPrice());
        assertEquals(2, logs.getHaPrice());
        assertFalse(logs.isStackable());
        assertArrayEquals(new String[]{null, null, null, null, "Drop"}, logs.getInventoryActions());
        assertArrayEquals(new String[]{null, null, "Light", null, null}, logs.getGroundActions());
        assertEquals(-1, logs.getNote());
        assertEquals(-1, logs.getPlaceholderTemplateId());
        assertTrue(client.getItemDefinition(995).isStackable());
        assertEquals("Abyssal whip (Members)", client.getItemDefinition(4151).getName(), "on a free world");
        assertTrue(client.getItemDefinition(4151).isMembers());
    }

    @Test
    void aNotedItemReportsItsNoteTemplate() {
        game.putConfigFile(ConfigTypes.OBJ, 799, new ConfigFile().op(2).str("Bank note").end());
        game.putConfigFile(ConfigTypes.OBJ, 1512, new ConfigFile().op(97).g2(1511).op(98).g2(799).end());
        Client fresh = new Client(game.memory, game.map);
        ItemComposition noted = fresh.getItemDefinition(1512);
        assertEquals(799, noted.getNote());
        assertEquals(1511, noted.getLinkedNoteId());
        assertEquals("Logs", noted.getName(), "a noted item takes the name of the item it notes");
    }

    @Test
    void paramsReadMissingValuesWhenUnset() {
        game.putConfigFile(ConfigTypes.OBJ, 2, new ConfigFile().op(2).str("Cannonball")
                .op(249).g1(2).g1(0).g3(10).g4(42).g1(1).g3(11).str("text").end());
        ItemComposition cannonball = new Client(game.memory, game.map).getItemDefinition(2);
        assertEquals(42, cannonball.getIntValue(10));
        assertEquals("text", cannonball.getStringValue(11));
        assertEquals(-1, cannonball.getIntValue(11), "a string param isn't an int");
        assertEquals("", cannonball.getStringValue(10));
        assertEquals(-1, cannonball.getIntValue(12), "unset: the API doesn't decode param defaults");
    }

    @Test
    void npcAndObjectDefinitions() {
        NPCComposition goblin = client.getNpcDefinition(3029);
        assertEquals("Goblin", goblin.getName());
        assertEquals(311, goblin.getCategory());
        assertEquals(1, goblin.getSize());
        assertArrayEquals(new String[5], goblin.getActions());
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, goblin.getStats());

        ObjectComposition door = client.getObjectDefinition(GameFixture.DOOR_LOC);
        assertEquals("Door", door.getName());
        assertEquals(1, door.getSizeX());
        assertEquals(2, door.getSizeY());
        assertEquals("Close", door.getActions()[0], "varp 10 is 100, inside the conditional op's 50-150");
        assertNull(door.getActions()[2]);
        assertEquals("<col=ff0000>Knock</col><lt>3", door.getActions()[1], "the client's tags are kept as they are");
        game.setVarp(10, 0);
        assertEquals("Open", door.getActions()[0], "actions resolve again on each call");
    }
}
