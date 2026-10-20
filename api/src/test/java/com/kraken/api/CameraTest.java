package com.kraken.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera, the canvas size and the world-to-canvas projection over the {@link GameFixture}. The fixture stores the
 * eye as the client does (x, height, z); the client's getters name the axes x, north (y) and height (z).
 */
class CameraTest {

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

    /**
     * Projects a point, given as the client stores fine positions (x, height, north), through the camera.
     *
     * @param x      the fine x
     * @param height the height (more negative is higher)
     * @param north  the fine north position
     * @return the canvas point, or null if the point is behind the camera
     */
    private Point project(int x, int height, int north) {
        return client.getCameraProjection().project(x, north, height);
    }

    @Test
    void readsCameraPositionLookAtAndAngles() {
        assertEquals(0, client.getCameraX());
        assertEquals(0, client.getCameraY(), "north");
        assertEquals(-1000, client.getCameraZ(), "height");
        assertEquals(0, client.getCameraFocalPointX());
        assertEquals(1024, client.getCameraFocalPointY(), "north");
        assertEquals(-1000, client.getCameraFocalPointZ(), "height");
        assertEquals(4096, client.getCameraPitch());
        assertEquals(8192, client.getCameraYaw());
        assertEquals(90f, client.getCameraPitch() * 360f / 16384f, 1e-3f, "pitch in degrees");
        assertEquals(180f, client.getCameraYaw() * 360f / 16384f, 1e-3f, "yaw in degrees");
        assertEquals(512, client.getScale());
        assertEquals(600, client.getCameraZoom());
    }

    @Test
    void controlAndTargetFollowThePluginGlobals() {
        assertFalse(client.isCameraControlled());
        assertFalse(client.hasCameraTarget());
        game.memory.putByte(GameFixture.MODULE_BASE + game.map.global("cameraControlled"), 1);
        game.memory.putInt(GameFixture.MODULE_BASE + game.map.global("cameraTarget"), 12);
        assertTrue(client.isCameraControlled());
        assertTrue(client.hasCameraTarget());
    }

    @Test
    void readsCanvasAndScreenSize() {
        assertEquals(765, client.getCanvasWidth());
        assertEquals(503, client.getCanvasHeight());
        assertEquals(1530, client.getScreenWidth());
        assertEquals(1006, client.getScreenHeight());
    }

    @Test
    void projectsAPointStraightAheadToTheViewportCentre() {
        // Window pixels (383, 251) at half scale, rounded half away from zero: (192, 126)
        assertEquals(new Point(192, 126), project(0, -1000, 1024));
    }

    @Test
    void projectsOffAxisPointsWithTheScaleOverDepth() {
        // dx 128, dy 256 at depth 1024 with scale 512: (64, 128) from the centre, so window (447, 379), client
        // (223.5, 189.5) rounded away from zero
        assertEquals(new Point(224, 190), project(128, -744, 1024));
        // To the left: window 383 - 64 = 319, client 159.5 -> 160
        assertEquals(new Point(160, 126), project(-128, -1000, 1024));
    }

    @Test
    void yawTurnsTheView() {
        // Yaw a quarter turn: the camera now looks along -x, so a point ahead there lands in the centre and one the
        // other way is behind it
        game.setCamera(0, -1000, 0, 0f, 1f, 1f, 0f);
        assertEquals(new Point(192, 126), project(-1024, -1000, 0));
        assertNull(project(1024, -1000, 0));
    }

    @Test
    void pointsBehindTheCameraHaveNoScreenPosition() {
        assertNull(project(0, -1000, -1024));
        assertNull(project(0, -1000, 0));
    }

    @Test
    void projectionReplaysTheClientsRounding() {
        CameraProjection projection = client.getCameraProjection();
        // Negative window coordinates round away from zero too: -1 * 0.5 = -0.5 -> -1
        CameraProjection left = new CameraProjection(0, 0, 0, 0f, 1f, 0f, 1f, 512, 0, 0, 0.5f, 0.5f);
        assertEquals(new Point(-1, 0), left.project(-2, 1024, 0));
        assertEquals(new Point(192, 126), projection.project(0, 1024, -1000));
    }

    @Test
    void cameraGettersNameNorthAndHeightAndKeepTheClientsAngles() {
        game.setCamera(1200, -900, 3400, 0f, 1f, 0f, 1f);
        assertEquals(1200, client.getCameraX());
        assertEquals(3400, client.getCameraY(), "north is the client's z");
        assertEquals(-900, client.getCameraZ(), "the height is the client's y");
        assertEquals(4096, client.getCameraPitch(), "JAU14, as RuneLite's");
        assertEquals(8192, client.getCameraYaw());
        assertEquals(512, client.getScale());
    }
}
