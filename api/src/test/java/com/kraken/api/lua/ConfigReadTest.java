package com.kraken.api.lua;

import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.lua.types.ConfigTypes;
import com.kraken.api.lua.types.VarBitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 4 reads over the {@link GameFixture}'s config archive: varbits, inventory sizes and item/NPC/loc
 * definitions, decoded from the archive's file bytes the way the client does.
 */
class ConfigReadTest {

    private GameFixture game;
    private ClientOp client;
    private ClientState state;
    private Engine engine;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        ConfigTypes configs = new ConfigTypes(game.memory, game.map);
        client = new ClientOp(game.memory, game.map, configs);
        state = new ClientState(game.memory, game.map, configs);
        engine = new Engine(client);
    }

    @Test
    void readsVarbitsFromTheirVarp() {
        // varp 10 = 100 = 0b0110_0100; varp 11 = 110 (bit 0 clear)
        assertEquals(0b0100, state.getVarbit(0));
        assertEquals(0b0110, state.getVarbit(1));
        assertEquals(0, state.getVarbit(2));
        assertEquals(new VarBitType(10, 4, 7), state.getVarbitType(1));
    }

    @Test
    void varbitsReadTheCopyTheClientsVarbitReaderUses() {
        game.setVarpBits(10, 0xf);
        assertEquals(0xf, state.getVarbit(0));
        assertEquals(100, state.getVarp(10), "getVarp still reads the server's copy");
    }

    @Test
    void varbitWithoutADefinitionReadsBitZeroOfVarpZeroLikeTheClient() {
        assertEquals(VarBitType.DEFAULT, state.getVarbitType(3));
        assertEquals(0, state.getVarbit(3));
        game.setVarp(0, 3);
        assertEquals(1, state.getVarbit(3));
        assertEquals(1, state.getVarbit(99_999), "past the group's last id too");
    }

    @Test
    void varbitIndexGroupsEveryIdByItsVarp() {
        Map<Integer, int[]> index = state.configs().varbitsByVarp();
        assertArrayEquals(new int[]{0, 1}, index.get(10));
        assertArrayEquals(new int[]{2}, index.get(11));
        assertArrayEquals(new int[]{3}, index.get(0), "the id without a file lands on varp 0, as in the client");
        assertEquals(6, state.getVarbit(4), "and the definitions it decoded serve getVarbit");
    }

    @Test
    void invSizeComesFromTheDefinition() {
        assertEquals(28, client.invSize(ClientOp.INV_BACKPACK));
        assertEquals(14, client.invSize(ClientOp.INV_WORN));
        assertEquals(0, client.invSize(ClientOp.INV_BANK), "no definition: 0, as in the client");
    }

    @Test
    void readsItemDefinitions() {
        assertEquals("Coins", client.ocName(995));
        assertTrue(client.ocStackable(995));
        assertEquals(1, client.ocCost(995));
        assertEquals("Logs", client.ocName(1511));
        assertFalse(client.ocStackable(1511));
        assertEquals(4, client.ocCost(1511));
        assertEquals("", client.ocName(99_999), "no definition: the client's default type");
        assertEquals(1, client.ocCost(99_999));
    }

    @Test
    void itemOpsAreOneBasedGroundOps() {
        assertEquals("Take", client.ocOp(995, 3), "the default ground op 3");
        assertEquals("Light", client.ocOp(1511, 3), "Logs' opcode 32 sets ground op 3");
        assertEquals("", client.ocOp(995, 1));
        assertEquals("", client.ocOp(995, 0));
        assertEquals("", client.ocOp(995, 6));
    }

    @Test
    void membersItemsAreRewrittenOnFreeWorlds() {
        assertEquals("Abyssal whip (Members)", client.ocName(4151), "world flags 0: a free world");
        game.setWorldFlags(1);
        assertEquals("Abyssal whip", client.ocName(4151));
    }

    @Test
    void readsNpcAndLocDefinitions() {
        assertEquals("Goblin", client.ncName(3029));
        assertEquals("", client.ncName(5), "no definition");
        assertEquals(311, engine.decodeNpcType(3029).getType());
        assertEquals("Coins", engine.decodeObjType(995).getName());
        assertEquals(GameFixture.DECOR_OFFSET, engine.decodeLocType(GameFixture.DECOR_LOC).getDecorHeightOffset());
    }

    @Test
    void newFilesNeedNewReaders() {
        game.putConfigFile(ConfigTypes.OBJ, 2, new ConfigFile().op(2).str("Cannonball").op(11).end());
        ClientOp fresh = new ClientOp(game.memory, game.map);
        assertEquals("Cannonball", fresh.ocName(2));
        assertEquals("Coins", fresh.ocName(995), "the rest of the group is still there");
    }

    @Test
    void anUnreadableArchiveGivesMissingValues() {
        game.memory.putLong(GameFixture.MODULE_BASE + game.map.global("configCache"), 0);
        ConfigTypes configs = new ConfigTypes(game.memory, game.map);
        ClientOp noArchive = new ClientOp(game.memory, game.map, configs);
        ClientState noArchiveState = new ClientState(game.memory, game.map, configs);
        assertFalse(configs.isAvailable());
        assertEquals("", noArchive.ocName(995));
        assertEquals(-1, noArchive.ocCost(995));
        assertEquals(-1, noArchive.invSize(ClientOp.INV_BACKPACK));
        assertEquals(-1, noArchiveState.getVarbit(0));
        assertNull(noArchive.getObjType(995));
    }
}
