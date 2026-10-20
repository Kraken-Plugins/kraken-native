package com.kraken.api;

import com.kraken.api.actor.Player;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.scene.WorldView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Perspective}'s canvas helpers over the {@link GameFixture}: the camera projection's axes (north second, height
 * third), the ground heights under each point, and null behind the camera. The fixture's
 * camera sits 1000 above local (0, 0) looking north.
 */
class PerspectiveTest {

    private GameFixture game;
    private Client client;

    @BeforeEach
    void setUp() throws IOException {
        game = new GameFixture();
        client = new Client(game.memory, game.map);
    }

    /**
     * The camera projection of a point, for comparing with the canvas helpers.
     *
     * @param x      the local x
     * @param y      the local y (north)
     * @param height the height
     * @return where the camera projection puts it
     */
    private Point project(int x, int y, int height) {
        return client.getCameraProjection().project(x, y, height);
    }

    private void flatTile(int sceneX, int sceneY, int height) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                game.setCornerHeight(0, sceneX + dx, sceneY + dy, height);
            }
        }
    }

    @Test
    void canvasSizeIsTheClientsOwnPixels() {
        assertEquals(765, client.getCanvasWidth());
        assertEquals(503, client.getCanvasHeight());
    }

    @Test
    void projectionTakesNorthSecondAndHeightThird() {
        // Straight ahead of the eye (north 1024 at the eye's height) lands on the viewport's centre
        assertEquals(new Point(192, 126), client.getCameraProjection().project(0, 1024, -1000));
        assertEquals(new Point(192, 126), Perspective.localToCanvas(client, 0, 1024, -1000));
        assertEquals(project(128, 1024, -744), Perspective.localToCanvas(client, 128, 1024, -744));
    }

    @Test
    void localToCanvasStandsOnTheGround() {
        flatTile(2, 3, -100);
        LocalPoint point = new LocalPoint(288, 480, WorldView.TOPLEVEL);
        assertEquals(-100, Perspective.getTileHeight(client, point, 0));
        assertEquals(project(288, 480, -100), Perspective.localToCanvas(client, point, 0));
        assertEquals(project(288, 480, -150), Perspective.localToCanvas(client, point, 0, 50));
    }

    @Test
    void tileHeightIsBilinearLikeTheClients() {
        game.setCornerHeight(0, 2, 3, -100);
        game.setCornerHeight(0, 3, 3, -200);
        game.setCornerHeight(0, 2, 4, -300);
        game.setCornerHeight(0, 3, 4, -400);
        LocalPoint point = new LocalPoint(288, 480, WorldView.TOPLEVEL);
        assertEquals(-275, Perspective.getTileHeight(client, point, 0), "a quarter in, three quarters north");
        assertEquals(-100, Perspective.getTileHeight(client, new LocalPoint(256, 384, WorldView.TOPLEVEL), 0));
    }

    @Test
    void tilePolyRunsRoundTheTilesCornersOnTheGround() {
        game.setCornerHeight(0, 2, 3, -100);
        game.setCornerHeight(0, 3, 3, -200);
        game.setCornerHeight(0, 3, 4, -300);
        game.setCornerHeight(0, 2, 4, -400);
        Polygon tile = Perspective.getCanvasTilePoly(client, LocalPoint.fromScene(2, 3, client.getTopLevelWorldView()));
        assertNotNull(tile);
        Point[] expected = {
            project(256, 384, -100), project(384, 384, -200), project(384, 512, -300), project(256, 512, -400)
        };
        assertEquals(4, tile.npoints);
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], new Point(tile.xpoints[i], tile.ypoints[i]), "corner " + i);
        }
    }

    @Test
    void anAreaPolySpansItsSizeAroundTheCentre() {
        // A 2x2 footprint centred on the corner shared by tiles (2, 3) to (3, 4)
        Polygon area = Perspective.getCanvasTileAreaPoly(client, new LocalPoint(384, 512, WorldView.TOPLEVEL), 2);
        assertNotNull(area);
        assertArrayEquals(new int[]{project(256, 384, 0).getX(), project(512, 384, 0).getX(), project(512, 640, 0).getX(),
                project(256, 640, 0).getX()}, area.xpoints);
    }

    @Test
    void aTileOnTheScenesEdgeTakesItsFarCornersHeightsFromJustInside() {
        // Tile (7, 3) is the last column of the 8x8 scene: its east corners sit on the scene's edge
        game.setCornerHeight(0, 8, 3, -500);
        game.setCornerHeight(0, 8, 4, -500);
        Polygon tile = Perspective.getCanvasTilePoly(client, LocalPoint.fromScene(7, 3, client.getTopLevelWorldView()));
        assertNotNull(tile);
        int inside = client.getTopLevelWorldView().getTileHeight(1023, 384, 0);
        assertTrue(inside <= -490, "the edge's height, not 0 for off the scene: " + inside);
        assertEquals(project(1024, 384, inside), new Point(tile.xpoints[1], tile.ypoints[1]));
    }

    @Test
    void pointsBehindTheCameraOrOutsideTheSceneHaveNoOutline() {
        LocalPoint centre = LocalPoint.fromScene(2, 3, client.getTopLevelWorldView());
        LocalPoint outside = LocalPoint.fromScene(20, 20, client.getTopLevelWorldView());
        assertNull(Perspective.getCanvasTilePoly(client, outside));
        assertNull(Perspective.localToCanvas(client, outside, 0), "no ground to stand it on");
        assertNull(Perspective.localToCanvas(client, new LocalPoint(288, 480, 3), 0), "another world view");

        // Move the eye north of the tile, still looking north: the tile is behind it
        game.setCamera(0, -1000, 1024, 0f, 1f, 0f, 1f);
        assertNull(Perspective.getCanvasTilePoly(client, centre));
        assertNull(Perspective.localToCanvas(client, centre, 0));
    }

    @Test
    void actorsPlaceTheirOutlineAndTextThroughPerspective() {
        Player self = client.getLocalPlayer();
        LocalPoint at = self.getLocalLocation();
        Polygon expected = Perspective.getCanvasTileAreaPoly(client, at, 1);
        Polygon tile = self.getCanvasTilePoly();
        assertNotNull(tile);
        assertArrayEquals(expected.xpoints, tile.xpoints);
        assertArrayEquals(expected.ypoints, tile.ypoints);

        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            Point centre = Perspective.localToCanvas(client, at, 0, 240);
            Point text = self.getCanvasTextLocation(graphics, "Zezima", 240);
            int width = (int) graphics.getFontMetrics().getStringBounds("Zezima", graphics).getWidth();
            assertEquals(new Point(centre.getX() - width / 2, centre.getY()), text);
        } finally {
            graphics.dispose();
        }
        BufferedImage icon = new BufferedImage(10, 6, BufferedImage.TYPE_INT_ARGB);
        Point centre = Perspective.localToCanvas(client, at, 0, 100);
        assertEquals(new Point(centre.getX() - 5, centre.getY() - 3), self.getCanvasImageLocation(icon, 100));
    }
}
