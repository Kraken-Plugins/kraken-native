package com.kraken.api.scene;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.actor.Actor;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.internal.MapCoord;
import com.kraken.api.internal.ProjectileEndpoint;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.kraken.api.GameFixture.ARROW_PROJECTILE;
import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.CYCLE;
import static com.kraken.api.GameFixture.KEYED_PROJECTILE;
import static com.kraken.api.GameFixture.MODULE_BASE;
import static com.kraken.api.GameFixture.PROJECTILE_KEY;
import static com.kraken.api.GameFixture.SPELL_PROJECTILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projectiles over the {@link GameFixture}: the client's list and its keyed projectiles, the axes (x, north y,
 * height z), the two endpoints as tiles, actors, heights and offsets, the live remaining cycles, and the "any
 * projectile drawn" check.
 */
class ProjectileTest {

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
    void listsProjectilesInListOrderThenTheKeyedOnes() {
        List<Projectile> projectiles = new ArrayList<>(client.getProjectiles());
        assertEquals(List.of(SPELL_PROJECTILE, ARROW_PROJECTILE, KEYED_PROJECTILE),
                projectiles.stream().map(Projectile::getId).toList());
        assertEquals(List.of(-1, -1, PROJECTILE_KEY), projectiles.stream().map(Projectile::getKey).toList());
    }

    @Test
    void readsAKeyedProjectileWithItsOffsets() {
        Projectile keyed = client.getProjectiles().getLast();
        assertEquals(new ProjectileEndpoint(new MapCoord(0, BASE_X + 1, BASE_Z + 2), 60, Actor.INTERACTING_PLAYER, 7,
                16, -16), keyed.getSource());
        assertEquals(new ProjectileEndpoint(new MapCoord(0, BASE_X + 4, BASE_Z + 4), 20, Actor.INTERACTING_NPC, 5,
                8, -8), keyed.getTarget());
        assertSame(client.getLocalPlayer(), keyed.getSourceActor());
        assertSame(client.getTopLevelWorldView().npcs().byIndex(5), keyed.getTargetActor());
        assertEquals(60, keyed.getStartHeight());
        assertEquals(20, keyed.getEndHeight());
        assertEquals(145, keyed.getStartCycle());
        assertEquals(185, keyed.getEndCycle());
        assertEquals(0, keyed.getSlope(), "a keyed projectile follows a curve");
        assertEquals(0, keyed.getStartPos());
        assertTrue(keyed.isStarted());
        assertEquals(250.5, keyed.getX());
        assertEquals(330.75, keyed.getY(), "north is the client's z");
        assertEquals(-90.25, keyed.getZ(), "the height is the client's y");
        assertEquals(1024, keyed.getOrientation());
        assertEquals(32, keyed.getPitch());
        assertFalse(keyed.isHidden());
        assertEquals(185 - CYCLE, keyed.getRemainingCycles());
    }

    @Test
    void anUnreadableKeyedMapLeavesTheListsProjectiles() {
        game.setKeyedProjectilesReadable(false);
        assertEquals(List.of(SPELL_PROJECTILE, ARROW_PROJECTILE),
                client.getProjectiles().stream().map(Projectile::getId).toList());
    }

    @Test
    void readsAPlacedProjectileAimedAtAnNpc() {
        Projectile spell = client.getProjectiles().getFirst();
        assertEquals(new ProjectileEndpoint(new MapCoord(0, BASE_X + 2, BASE_Z + 3), 43, Actor.INTERACTING_PLAYER, 7),
                spell.getSource());
        assertEquals(new ProjectileEndpoint(new MapCoord(0, BASE_X + 4, BASE_Z + 4), 31, Actor.INTERACTING_NPC, 5),
                spell.getTarget());
        assertEquals(140, spell.getStartCycle());
        assertEquals(170, spell.getEndCycle());
        assertEquals(16, spell.getSlope());
        assertEquals(64, spell.getStartPos());
        assertTrue(spell.isStarted());
        assertEquals(300.75, spell.getX());
        assertEquals(420.25, spell.getY(), "north is the client's z");
        assertEquals(-150.5, spell.getZ(), "the height is the client's y");
        assertEquals(1536, spell.getOrientation());
        assertEquals(100, spell.getPitch());
        assertFalse(spell.isHidden());
        assertEquals(CYCLE, spell.getReadCycle());
        assertEquals(170 - CYCLE, spell.getRemainingCycles());
    }

    @Test
    void endpointsAreWorldTilesWithHeightsAndActors() {
        Projectile spell = client.getProjectiles().getFirst();
        assertEquals(new WorldPoint(BASE_X + 2, BASE_Z + 3, 0), spell.getSourcePoint());
        assertEquals(0, spell.getSourceLevel());
        assertEquals(43, spell.getStartHeight());
        assertSame(client.getLocalPlayer(), spell.getSourceActor());
        assertEquals(new WorldPoint(BASE_X + 4, BASE_Z + 4, 0), spell.getTargetPoint());
        assertEquals(0, spell.getTargetLevel());
        assertEquals(31, spell.getEndHeight());
        assertSame(client.getTopLevelWorldView().npcs().byIndex(5), spell.getTargetActor());
    }

    @Test
    void aProjectileThatHasNotSetOffHasNoActorAtItsStart() {
        Projectile arrow = new ArrayList<>(client.getProjectiles()).get(1);
        assertFalse(arrow.isStarted());
        assertNull(arrow.getSourceActor(), "a tile");
        assertEquals(new WorldPoint(BASE_X + 6, BASE_Z + 6, 0), arrow.getTargetPoint());
        assertEquals(36, arrow.getEndHeight());
        assertTrue(arrow.isHidden());
    }

    @Test
    void remainingCyclesReadTheCycleNow() {
        Projectile spell = client.getProjectiles().getFirst();
        assertEquals(170 - CYCLE, spell.getRemainingCycles());
        game.memory.putInt(MODULE_BASE + game.map.global("cycle"), CYCLE + 5);
        assertEquals(170 - CYCLE - 5, spell.getRemainingCycles());
    }

    @Test
    void projectilesAreRenderedWhileAnyIsDrawn() {
        assertTrue(client.areProjectilesRendered());
        game.setProjectileHidden(0, true);
        assertFalse(client.areProjectilesRendered());
        game.setProjectileHidden(1, false);
        assertTrue(client.areProjectilesRendered());
        game.setProjectileHidden(1, true);
        assertFalse(client.areProjectilesRendered(), "the keyed projectile isn't counted, as in the Lua handler");
    }

    @Test
    void nothingInFlight() {
        game.clearProjectiles();
        game.setKeyedProjectileInFlight(false);
        assertTrue(client.getProjectiles().isEmpty());
        assertFalse(client.areProjectilesRendered());
    }

    @Test
    void aMappingWithoutTheProjectileKeysHasNothingInFlight(@TempDir Path dir) throws IOException {
        JsonObject json;
        try (InputStreamReader in = new InputStreamReader(Mapping.class.getClassLoader()
                .getResourceAsStream(Mapping.RESOURCE_DIR + game.map.version() + ".json"), StandardCharsets.UTF_8)) {
            json = new Gson().fromJson(in, JsonObject.class);
        }
        JsonObject structs = json.getAsJsonObject("structs");
        structs.remove("KeyedProjectile");
        structs.getAsJsonObject("Client").remove("keyedProjectiles");
        Client withoutKeyed = new Client(game.memory, Mapping.load(write(dir, json)));
        assertEquals(List.of(SPELL_PROJECTILE, ARROW_PROJECTILE),
                withoutKeyed.getProjectiles().stream().map(Projectile::getId).toList());

        structs.remove("Projectile");
        structs.getAsJsonObject("Client").remove("projectiles");
        Client older = new Client(game.memory, Mapping.load(write(dir, json)));
        assertTrue(older.getProjectiles().isEmpty());
        assertFalse(older.areProjectilesRendered());
    }

    private Path write(Path dir, JsonObject json) throws IOException {
        Path file = dir.resolve(game.map.version() + ".json");
        Files.writeString(file, json.toString());
        return file;
    }
}
