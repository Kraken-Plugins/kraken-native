package com.kraken.api.impl;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.EquipmentInventorySlot;
import com.kraken.api.GameFixture;
import com.kraken.api.GameState;
import com.kraken.api.Item;
import com.kraken.api.ItemComposition;
import com.kraken.api.ItemContainer;
import com.kraken.api.NPCComposition;
import com.kraken.api.ObjectComposition;
import com.kraken.api.ProfileType;
import com.kraken.api.Skill;
import com.kraken.api.WorldType;
import com.kraken.api.gameval.InventoryID;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.lua.types.ConfigTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RuneLite-shaped {@link Client} over the {@link GameFixture}: game state, skills, vars, inventories and
 * definitions, each checked against what the Lua mirror reads for the same fixture.
 */
class ClientAdapterTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = Adapters.client(new LuaApi(game.memory, game.map));
    }

    @Test
    void gameStateMapsTheRawValue() {
        assertEquals(GameState.LOGGED_IN, client.getGameState());
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
    void skillsAreIndexedByLuaStatId() {
        assertEquals(99, client.getRealSkillLevel(Skill.ATTACK));
        assertEquals(10, client.getRealSkillLevel(Skill.HITPOINTS));
        assertEquals(12, client.getBoostedSkillLevel(Skill.HITPOINTS));
        assertEquals(1_154, client.getSkillExperience(Skill.HITPOINTS));
        game.setStat(Skill.RUNECRAFT.ordinal(), 2_000, 15, 14);
        assertEquals(14, client.getRealSkillLevel(Skill.RUNECRAFT));
        assertEquals(14, client.getRealSkillLevels()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(15, client.getBoostedSkillLevels()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(2_000, client.getSkillExperiences()[Skill.RUNECRAFT.ordinal()]);
        assertEquals(99 + 10 + 14 + Skill.values().length - 3, client.getTotalLevel(), "every other skill is 1");
    }

    @Test
    void skillsReadMinusOneBeforeTheServerSendsThem() {
        game.setStatsSent(false);
        assertEquals(-1, client.getRealSkillLevel(Skill.ATTACK));
        assertEquals(-1, client.getTotalLevel());
        int[] levels = client.getRealSkillLevels();
        assertTrue(levels.length >= Skill.values().length);
        assertTrue(Arrays.stream(levels).allMatch(level -> level == -1));
    }

    @Test
    void enumsLineUpWithTheLuaOnes() {
        ClientState.Stats[] stats = ClientState.Stats.values();
        assertEquals(stats.length, Skill.values().length);
        for (ClientState.Stats stat : stats) {
            Skill skill = Skill.values()[stat.id()];
            String expected = stat == ClientState.Stats.SKILL_RUNECRAFTING ? "Runecraft" : stat.displayName();
            assertEquals(expected, skill.getName());
        }
        for (ClientOp.WornSlots slot : ClientOp.WornSlots.values()) {
            assertEquals(slot.slot(), EquipmentInventorySlot.valueOf(slot.name()).getSlotIdx());
        }
        assertEquals(ClientOp.INV_BACKPACK, InventoryID.INV);
        assertEquals(ClientOp.INV_WORN, InventoryID.WORN);
        assertEquals(ClientOp.INV_BANK, InventoryID.BANK);
    }

    @Test
    void varpsReadTheClientsCopyAndTheServersOnRequest() {
        assertEquals(100, client.getVarpValue(10));
        game.setVarpBits(10, 0xf);
        assertEquals(0xf, client.getVarpValue(10), "a change the client made locally, as RuneLite's getVarpValue");
        assertEquals(100, client.getServerVarpValue(10));
        assertEquals(0xf, client.getVarps()[10]);
        assertEquals(100, client.getServerVarps()[10]);
        assertEquals(GameFixture.VARP_COUNT, client.getVarps().length);
        assertEquals(-1, client.getVarpValue(GameFixture.VARP_COUNT), "outside the varp array");
        assertEquals(-1, client.getVarpValue(-1));
        assertEquals(0xf, client.getVarbitValue(0), "varbit 0 is bits 0-3 of varp 10");
    }

    @Test
    void varcsReadZeroWhenUnsetAsInRuneLite() {
        assertEquals(42, client.getVarcIntValue(10));
        assertEquals(-5, client.getVarcIntValue(30));
        assertEquals(0, client.getVarcIntValue(11));
        assertEquals("hello", client.getVarcStrValue(20));
        assertEquals("", client.getVarcStrValue(21));
    }

    @Test
    void anItemContainerIsASnapshotOfTheInventory() {
        ItemContainer backpack = client.getItemContainer(InventoryID.INV);
        assertEquals(InventoryID.INV, backpack.getId());
        assertArrayEquals(new Item[]{new Item(1511, 1), new Item(995, 100), new Item(-1, 0), new Item(995, 50)},
                backpack.getItems());
        assertEquals(28, backpack.size(), "from the inventory's definition");
        assertEquals(3, backpack.count());
        assertEquals(150, backpack.count(995));
        assertEquals(0, backpack.count(-1));
        assertEquals(1, backpack.find(995));
        assertEquals(-1, backpack.find(4151));
        assertTrue(backpack.contains(1511));
        assertFalse(backpack.contains(4151));
        assertEquals(new Item(995, 100), backpack.getItem(1));
        assertNull(backpack.getItem(2), "an empty slot");
        assertNull(backpack.getItem(4), "past the slots the client holds");

        game.setInvSlot(InventoryID.INV, 0, 4151, 1);
        assertEquals(1511, backpack.getItem(0).getId(), "the snapshot doesn't change");
        assertEquals(4151, client.getItemContainer(InventoryID.INV).getItem(0).getId());
    }

    @Test
    void wornSlotsAreEquipmentInventorySlots() {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        assertEquals(4151, worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx()).getId());
        assertEquals(14, worn.size());
    }

    @Test
    void anInventoryWithoutADefinitionIsAsBigAsItsSlots() {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        assertEquals(0, bank.size());
        assertEquals(0, bank.getItems().length);
        assertNull(client.getItemContainer(12_345), "an inventory the client doesn't hold");
    }

    @Test
    void itemDefinitionsUseRuneLitesShapes() {
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
        Client fresh = Adapters.client(new LuaApi(game.memory, game.map));
        ItemComposition noted = fresh.getItemDefinition(1512);
        assertEquals(799, noted.getNote());
        assertEquals(1511, noted.getLinkedNoteId());
        assertEquals("Logs", noted.getName(), "a noted item takes the name of the item it notes");
    }

    @Test
    void paramsReadMissingValuesWhenUnset() {
        game.putConfigFile(ConfigTypes.OBJ, 2, new ConfigFile().op(2).str("Cannonball")
                .op(249).g1(2).g1(0).g3(10).g4(42).g1(1).g3(11).str("text").end());
        ItemComposition cannonball = Adapters.client(new LuaApi(game.memory, game.map)).getItemDefinition(2);
        assertEquals(42, cannonball.getIntValue(10));
        assertEquals("text", cannonball.getStringValue(11));
        assertEquals(-1, cannonball.getIntValue(11), "a string param isn't an int");
        assertEquals("", cannonball.getStringValue(10));
        assertEquals(-1, cannonball.getIntValue(12), "unset: Kraken doesn't decode param defaults");
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
        game.setVarp(10, 0);
        assertEquals("Open", door.getActions()[0], "actions resolve again on each call");
    }
}
