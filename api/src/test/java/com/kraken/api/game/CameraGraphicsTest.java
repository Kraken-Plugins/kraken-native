package com.kraken.api.game;

import com.kraken.api.GameFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 5 reads over the {@link GameFixture}: the {@code Camera} getters, the client's size and the world-to-screen
 * projection.
 */
class CameraGraphicsTest {

    private GameFixture game;
    private Camera camera;
    private Graphics graphics;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        camera = new Camera(game.memory, game.map);
        graphics = new Graphics(game.memory, game.map);
    }

    @Test
    void readsCameraPositionLookAtAndAngles() {
        assertEquals(new FineCoord(0, -1000, 0), camera.getPos());
        assertEquals(new FineCoord(0, -1000, 1024), camera.getLookAt());
        assertEquals(4096, camera.getPitch());
        assertEquals(8192, camera.getYaw());
        Vector2 angles = camera.getLookAtAngles();
        assertEquals(90f, angles.x(), 1e-3f);
        assertEquals(180f, angles.y(), 1e-3f);
        assertEquals(512, camera.getScale());
        assertEquals(600, camera.getZoom());
    }

    @Test
    void anglesUseTheClientsFloatConstants() {
        // The degree constant is one ulp below the nearest float to 180/pi, as stored in the client
        assertEquals(0x39c90fdb, Float.floatToRawIntBits(Camera.RADIANS_PER_UNIT));
        assertEquals(0x42652ee0, Float.floatToRawIntBits(Camera.DEGREES_PER_RADIAN));
        assertEquals((float) 2868 * Camera.RADIANS_PER_UNIT * Camera.DEGREES_PER_RADIAN, Camera.toDegrees(2868));
    }

    @Test
    void controlAndTargetFollowThePluginGlobals() {
        assertFalse(camera.isControlled());
        assertFalse(camera.hasTarget());
        game.memory.putByte(GameFixture.MODULE_BASE + game.map.global("cameraControlled"), 1);
        game.memory.putInt(GameFixture.MODULE_BASE + game.map.global("cameraTarget"), 12);
        assertTrue(camera.isControlled());
        assertTrue(camera.hasTarget());
    }

    @Test
    void readsClientDimensionsAndScreenSize() {
        assertEquals(new Vector2(765, 503), graphics.clientDimensions());
        assertEquals(new Vector2(1530, 1006), graphics.screenSize());
    }

    @Test
    void projectsAPointStraightAheadToTheViewportCentre() {
        // Window pixels (383, 251) at half scale, rounded half away from zero: (192, 126)
        assertEquals(new Vector2(192, 126), graphics.worldToScreenCoord(new FineCoord(0, -1000, 1024)));
    }

    @Test
    void projectsOffAxisPointsWithTheScaleOverDepth() {
        // dx 128, dy 256 at depth 1024 with scale 512: (64, 128) from the centre, so window (447, 379), client
        // (223.5, 189.5) rounded away from zero
        assertEquals(new Vector2(224, 190), graphics.worldToScreenCoord(new FineCoord(128, -744, 1024)));
        // To the left: window 383 - 64 = 319, client 159.5 -> 160
        assertEquals(new Vector2(160, 126), graphics.worldToScreenCoord(new FineCoord(-128, -1000, 1024)));
    }

    @Test
    void yawTurnsTheView() {
        // Yaw a quarter turn: the camera now looks along -x, so a point ahead there lands in the centre and one the
        // other way is behind it
        game.setCamera(0, -1000, 0, 0f, 1f, 1f, 0f);
        assertEquals(new Vector2(192, 126), graphics.worldToScreenCoord(new FineCoord(-1024, -1000, 0)));
        assertNull(graphics.worldToScreenCoord(new FineCoord(1024, -1000, 0)));
    }

    @Test
    void pointsBehindTheCameraHaveNoScreenPosition() {
        assertNull(graphics.worldToScreenCoord(new FineCoord(0, -1000, -1024)));
        assertNull(graphics.worldToScreenCoord(new FineCoord(0, -1000, 0)));
    }

    @Test
    void projectionReplaysTheClientsRounding() {
        Graphics.Projection projection = graphics.projection();
        // Negative window coordinates round away from zero too: -1 * 0.5 = -0.5 -> -1
        Graphics.Projection left = new Graphics.Projection(0, 0, 0, 0f, 1f, 0f, 1f, 512, 0, 0, 0.5f, 0.5f);
        assertEquals(new Vector2(-1, 0), left.project(-2, 0, 1024));
        assertEquals(new Vector2(192, 126), projection.project(0, -1000, 1024));
    }
}
