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
import static com.kraken.api.GameFixture.MODULE_BASE;
import static com.kraken.api.GameFixture.SPELL_PROJECTILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projectiles over the {@link GameFixture}: the client's list, RuneLite's axes (x, north y, height z), the two
 * endpoints as tiles, actors and heights, the live remaining cycles, and the "any projectile drawn" check.
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
    void listsProjectilesInListOrder() {
        List<Projectile> projectiles = new ArrayList<>(client.getProjectiles());
        assertEquals(List.of(SPELL_PROJECTILE, ARROW_PROJECTILE), projectiles.stream().map(Projectile::getId).toList());
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
        Projectile arrow = client.getProjectiles().getLast();
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
    }

    @Test
    void nothingInFlight() {
        game.clearProjectiles();
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
        structs.remove("Projectile");
        structs.getAsJsonObject("Client").remove("projectiles");
        Path file = dir.resolve(game.map.version() + ".json");
        Files.writeString(file, json.toString());

        Client older = new Client(game.memory, Mapping.load(file));
        assertTrue(older.getProjectiles().isEmpty());
        assertFalse(older.areProjectilesRendered());
    }
}
