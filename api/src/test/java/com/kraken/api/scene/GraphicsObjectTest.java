package com.kraken.api.scene;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kraken.api.Client;
import com.kraken.api.GameFixture;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.coords.WorldPoint;
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

import static com.kraken.api.GameFixture.BASE_X;
import static com.kraken.api.GameFixture.BASE_Z;
import static com.kraken.api.GameFixture.SMOKE_GRAPHIC;
import static com.kraken.api.GameFixture.SPLASH_GRAPHIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graphics objects over the {@link GameFixture}: the top-level world's list, the tile and height, the animation
 * frame, identity across reads, and the live {@code finished} check.
 */
class GraphicsObjectTest {

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

    private List<GraphicsObject> read() {
        return new ArrayList<>(client.getGraphicsObjects());
    }

    @Test
    void listsGraphicsObjectsInListOrder() {
        assertEquals(List.of(SPLASH_GRAPHIC, SMOKE_GRAPHIC), read().stream().map(GraphicsObject::getId).toList());
        assertEquals(read(), new ArrayList<>(client.getTopLevelWorldView().getGraphicsObjects()));
    }

    @Test
    void readsTheTileHeightCycleAndFrame() {
        GraphicsObject splash = read().get(0);
        assertEquals(new LocalPoint(3 * 128 + 64, 4 * 128 + 64, WorldView.TOPLEVEL), splash.getLocation());
        assertEquals(new WorldPoint(BASE_X + 3, BASE_Z + 4, 0), WorldPoint.fromLocal(client, splash.getLocation()));
        assertEquals(0, splash.getLevel());
        assertEquals(0, splash.getZ());
        assertEquals(145, splash.getStartCycle());
        assertEquals(3, splash.getAnimationFrame());
        assertSame(client.getTopLevelWorldView(), splash.getWorldView());

        GraphicsObject smoke = read().get(1);
        assertEquals(new LocalPoint(5 * 128 + 64, 2 * 128 + 64, WorldView.TOPLEVEL), smoke.getLocation());
        assertEquals(-40, smoke.getZ(), "raised above the ground: more negative is higher");
        assertEquals(160, smoke.getStartCycle());
        assertEquals(-1, smoke.getAnimationFrame(), "not started yet");
    }

    @Test
    void snapshotsOfTheSameObjectAreEqualWhateverTheFrame() {
        GraphicsObject before = read().get(0);
        game.setGraphicsObjectFrame(0, 7);
        GraphicsObject after = read().get(0);
        assertEquals(7, after.getAnimationFrame());
        assertEquals(3, before.getAnimationFrame(), "a snapshot keeps the frame of its read");
        assertEquals(before, after);
        assertEquals(before.hashCode(), after.hashCode());
        assertNotEquals(read().get(1), after);

        game.setGraphicsObjectStartCycle(0, 400);
        assertNotEquals(before, read().get(0), "another object played in the same memory");
    }

    @Test
    void finishedFollowsTheAnimationAndTheList() {
        GraphicsObject splash = read().get(0);
        assertFalse(splash.finished());
        game.setGraphicsObjectEnded(0, true);
        assertTrue(splash.finished(), "the animation ended: dropped on the next frame");
        game.setGraphicsObjectEnded(0, false);
        game.setGraphicsObjectsPlaying(1);
        assertTrue(splash.finished(), "no longer in the list");
        game.setGraphicsObjectsPlaying(0, 1);
        game.setGraphicsObjectStartCycle(0, 400);
        assertTrue(splash.finished(), "another object took its memory");
    }

    @Test
    void anUnreadableListReadsAsEmptyAndFinishedFallsBackOnTheSnapshot() {
        GraphicsObject splash = read().get(0);
        game.breakGraphicsObjectList();
        assertTrue(client.getGraphicsObjects().isEmpty());
        assertFalse(splash.finished(), "as of its read");
    }

    @Test
    void nothingPlaying() {
        game.setGraphicsObjectsPlaying();
        assertTrue(client.getGraphicsObjects().isEmpty());
    }

    @Test
    void aMappingWithoutTheGraphicsObjectKeysHasNothingPlaying(@TempDir Path dir) throws IOException {
        JsonObject json;
        try (InputStreamReader in = new InputStreamReader(Mapping.class.getClassLoader()
                .getResourceAsStream(Mapping.RESOURCE_DIR + game.map.version() + ".json"), StandardCharsets.UTF_8)) {
            json = new Gson().fromJson(in, JsonObject.class);
        }
        JsonObject structs = json.getAsJsonObject("structs");
        structs.remove("GraphicsObject");
        structs.getAsJsonObject("World").remove("graphicsObjects");
        Path file = dir.resolve(game.map.version() + ".json");
        Files.writeString(file, json.toString());

        Client older = new Client(game.memory, Mapping.load(file));
        assertTrue(older.getGraphicsObjects().isEmpty());
    }
}
