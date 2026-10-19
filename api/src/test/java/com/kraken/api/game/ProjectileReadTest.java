package com.kraken.api.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kraken.api.GameFixture;
import com.kraken.api.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.kraken.api.GameFixture.ARROW_PROJECTILE;
import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.CYCLE;
import static com.kraken.api.GameFixture.SPELL_PROJECTILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the {@link GameFixture}'s projectile list through {@link Client}: the list walk, each projectile's fields and
 * endpoint decoding, and {@code areProjectilesRendered}'s "any projectile drawn" check.
 */
class ProjectileReadTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    @Test
    void listsProjectilesInListOrder() {
        List<Projectile> projectiles = client.getProjectiles();
        assertEquals(2, projectiles.size());
        assertEquals(SPELL_PROJECTILE, projectiles.get(0).id());
        assertEquals(ARROW_PROJECTILE, projectiles.get(1).id());
    }

    @Test
    void readsAPlacedProjectileAimedAtAnNpc() {
        Projectile spell = client.getProjectiles().get(0);
        assertEquals(new Projectile.Endpoint(new MapCoord(0, BASE_X + 2, BASE_Z + 3), 43, Entity.TARGET_PLAYER, 7),
                spell.source());
        assertEquals(new Projectile.Endpoint(new MapCoord(0, BASE_X + 4, BASE_Z + 4), 31, Entity.TARGET_NPC, 5),
                spell.target());
        assertEquals(140, spell.startCycle());
        assertEquals(170, spell.endCycle());
        assertEquals(16, spell.slope());
        assertEquals(64, spell.startDistance());
        assertTrue(spell.started());
        assertEquals(300.75, spell.x());
        assertEquals(-150.5, spell.y());
        assertEquals(420.25, spell.z());
        assertEquals(new FineCoord(300, -150, 420), spell.position());
        assertEquals(1536, spell.yaw());
        assertEquals(100, spell.pitch());
        assertTrue(spell.isRendered());
        assertEquals(CYCLE, spell.cycle());
        assertEquals(170 - CYCLE, spell.remainingCycles());
    }

    @Test
    void aProjectileThatHasNotSetOffHasNoPosition() {
        Projectile arrow = client.getProjectiles().get(1);
        assertFalse(arrow.started());
        assertTrue(arrow.position().isMissing());
        assertFalse(arrow.source().isActor());
        assertEquals(-1, arrow.source().actorIndex());
        assertEquals(new MapCoord(0, BASE_X + 6, BASE_Z + 6), arrow.target().tile());
        assertEquals(36, arrow.target().height());
        assertFalse(arrow.isRendered());
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
