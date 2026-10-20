package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.ConfigFile;
import com.kraken.api.GameFixture;
import com.kraken.api.definition.ItemComposition;
import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.gameval.InventoryID;
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
 * The config archive over the {@link GameFixture}: varbits, inventory sizes and item, NPC and loc definitions, decoded
 * from the archive's file bytes the way the client does.
 */
class ConfigTypesTest {

    private static final int COINS = 995;
    private static final int LOGS = 1511;
    private static final int WHIP = 4151;
    private static final int GOBLIN = 3029;

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
    void readsVarbitsFromTheirVarp() {
        // varp 10 = 100 = 0b0110_0100; varp 11 = 110 (bit 0 clear)
        assertEquals(0b0100, client.getVarbitValue(0));
        assertEquals(0b0110, client.getVarbitValue(1));
        assertEquals(0, client.getVarbitValue(2));
        VarbitComposition varbit = client.getVarbit(1);
        assertEquals(10, varbit.getIndex());
        assertEquals(4, varbit.getLeastSignificantBit());
        assertEquals(7, varbit.getMostSignificantBit());
    }

    @Test
    void varbitsReadTheCopyTheClientsVarbitReaderUses() {
        game.setVarpBits(10, 0xf);
        assertEquals(0xf, client.getVarbitValue(0));
        assertEquals(100, client.getServerVarpValue(10), "the server's copy is unchanged");
    }

    @Test
    void varbitWithoutADefinitionReadsBitZeroOfVarpZeroLikeTheClient() {
        assertEquals(VarbitComposition.DEFAULT, client.getVarbit(3));
        assertEquals(0, client.getVarbitValue(3));
        game.setVarp(0, 3);
        assertEquals(1, client.getVarbitValue(3));
        assertEquals(1, client.getVarbitValue(99_999), "past the group's last id too");
    }

    @Test
    void varbitIndexGroupsEveryIdByItsVarp() {
        Map<Integer, int[]> index = client.getConfigTypes().varbitsByVarp();
        assertArrayEquals(new int[]{0, 1}, index.get(10));
        assertArrayEquals(new int[]{2}, index.get(11));
        assertArrayEquals(new int[]{3}, index.get(0), "the id without a file lands on varp 0, as in the client");
        assertEquals(6, client.getVarbitValue(4), "and the definitions it decoded serve getVarbitValue");
    }

    @Test
    void invSizeComesFromTheDefinition() {
        assertEquals(28, client.getConfigTypes().invSize(InventoryID.INV));
        assertEquals(14, client.getConfigTypes().invSize(InventoryID.WORN));
        assertEquals(0, client.getConfigTypes().invSize(InventoryID.BANK), "no definition: 0, as in the client");
    }

    @Test
    void readsItemDefinitions() {
        ItemComposition coins = client.getItemDefinition(COINS);
        assertEquals("Coins", coins.getName());
        assertTrue(coins.isStackable());
        assertEquals(1, coins.getPrice());
        ItemComposition logs = client.getItemDefinition(LOGS);
        assertEquals("Logs", logs.getName());
        assertFalse(logs.isStackable());
        assertEquals(4, logs.getPrice());
        assertEquals("", client.getItemDefinition(99_999).getName(), "no definition: the client's default type");
        assertEquals(1, client.getItemDefinition(99_999).getPrice());
    }

    @Test
    void itemGroundOpsAreOneBasedInTheClient() {
        // Ground op n is index n - 1 of the ground actions array; empty ops read as null
        assertEquals("Take", client.getItemDefinition(COINS).getGroundActions()[2], "the default ground op 3");
        assertEquals("Light", client.getItemDefinition(LOGS).getGroundActions()[2], "Logs' opcode 32 sets ground op 3");
        assertNull(client.getItemDefinition(COINS).getGroundActions()[0]);
    }

    @Test
    void membersItemsAreRewrittenOnFreeWorlds() {
        assertEquals("Abyssal whip (Members)", client.getItemDefinition(WHIP).getName(), "world flags 0: a free world");
        game.setWorldFlags(1);
        assertEquals("Abyssal whip", client.getItemDefinition(WHIP).getName());
    }

    @Test
    void readsNpcAndLocDefinitions() {
        assertEquals("Goblin", client.getNpcDefinition(GOBLIN).getName());
        assertEquals("", client.getNpcDefinition(5).getName(), "no definition");
        assertEquals(311, client.getNpcDefinition(GOBLIN).getCategory());
        assertEquals("Coins", client.getItemDefinition(COINS).getName());
        assertEquals(GameFixture.DECOR_OFFSET,
                client.getObjectDefinition(GameFixture.DECOR_LOC).getDecorHeightOffset());
    }

    @Test
    void newFilesNeedANewClient() {
        game.putConfigFile(ConfigTypes.OBJ, 2, new ConfigFile().op(2).str("Cannonball").op(11).end());
        Client fresh = new Client(game.memory, game.map);
        assertEquals("Cannonball", fresh.getItemDefinition(2).getName());
        assertEquals("Coins", fresh.getItemDefinition(COINS).getName(), "the rest of the group is still there");
    }

    @Test
    void anUnreadableArchiveGivesMissingValues() {
        game.memory.putLong(GameFixture.MODULE_BASE + game.map.global("configCache"), 0);
        Client noArchive = new Client(game.memory, game.map);
        assertFalse(noArchive.getConfigTypes().isAvailable());
        assertNull(noArchive.getItemDefinition(COINS), "a definition can't be read without the archive");
        assertEquals(-1, noArchive.getConfigTypes().invSize(InventoryID.INV));
        assertEquals(-1, noArchive.getVarbitValue(0));
    }
}
