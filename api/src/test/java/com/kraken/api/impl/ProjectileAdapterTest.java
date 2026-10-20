package com.kraken.api.impl;

import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.Projectile;
import com.kraken.api.coords.WorldPoint;
import com.kraken.api.lua.LuaApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * The RuneLite-shaped {@link Projectile} and the camera getters over the {@link GameFixture}: RuneLite's axes, the
 * endpoint handles and the live remaining cycles.
 */
class ProjectileAdapterTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = Adapters.client(new LuaApi(game.memory, game.map));
    }

    @Test
    void projectilesAreTheClientsListWithRuneLitesAxes() {
        List<Projectile> projectiles = new ArrayList<>(client.getProjectiles());
        assertEquals(List.of(SPELL_PROJECTILE, ARROW_PROJECTILE), projectiles.stream().map(Projectile::getId).toList());
        Projectile spell = projectiles.get(0);
        assertEquals(300.75, spell.getX());
        assertEquals(420.25, spell.getY(), "north is Lua's z");
        assertEquals(-150.5, spell.getZ(), "the height is Lua's y");
        assertEquals(1536, spell.getOrientation());
        assertEquals(100, spell.getPitch());
        assertEquals(140, spell.getStartCycle());
        assertEquals(170, spell.getEndCycle());
        assertEquals(16, spell.getSlope());
        assertEquals(64, spell.getStartPos());
        assertEquals(43, spell.getStartHeight());
        assertEquals(31, spell.getEndHeight());
        assertTrue(spell.isStarted());
        assertFalse(spell.isHidden());
        assertEquals(new WorldPoint(BASE_X + 2, BASE_Z + 3, 0), spell.getSourcePoint());
        assertEquals(0, spell.getTargetLevel());
        assertSame(client.getLocalPlayer(), spell.getSourceActor());
        assertSame(client.getTopLevelWorldView().npcs().byIndex(5), spell.getTargetActor());

        Projectile arrow = projectiles.get(1);
        assertTrue(arrow.isHidden());
        assertFalse(arrow.isStarted());
        assertNull(arrow.getSourceActor(), "a tile");
        assertEquals(new WorldPoint(BASE_X + 6, BASE_Z + 6, 0), arrow.getTargetPoint());
        assertTrue(client.areProjectilesRendered(), "the spell is drawn");
    }

    @Test
    void remainingCyclesReadTheCycleNow() {
        Projectile spell = client.getProjectiles().getFirst();
        assertEquals(170 - CYCLE, spell.getRemainingCycles());
        game.memory.putInt(MODULE_BASE + game.map.global("cycle"), CYCLE + 5);
        assertEquals(170 - CYCLE - 5, spell.getRemainingCycles());
    }

    @Test
    void anEmptyListIsEmpty() {
        game.clearProjectiles();
        assertTrue(client.getProjectiles().isEmpty());
        assertFalse(client.areProjectilesRendered());
    }

    @Test
    void cameraGettersSwapLuasAxesAndKeepItsAngles() {
        game.setCamera(1200, -900, 3400, 0f, 1f, 0f, 1f);
        assertEquals(1200, client.getCameraX());
        assertEquals(3400, client.getCameraY(), "north is Lua's z");
        assertEquals(-900, client.getCameraZ(), "the height is Lua's y");
        assertEquals(4096, client.getCameraPitch(), "JAU14, as RuneLite's");
        assertEquals(8192, client.getCameraYaw());
        assertEquals(512, client.getScale());
    }
}
