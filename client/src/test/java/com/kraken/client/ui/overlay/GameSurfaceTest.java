package com.kraken.client.ui.overlay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GameSurface}: the mapping it lays out for the DLL, the slots it writes, the rows it copies and when it gives
 * the game up. The mapping is a heap buffer standing in for the shared memory.
 */
class GameSurfaceTest {

    private static final long PID = 1234;
    private static final int GL_4_6 = 4 << 16 | 6;

    private final List<FakeRegion> regions = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private long now;
    private GameSurface surface;

    @BeforeEach
    void setUp() {
        surface = new GameSurface((name, size) -> {
            FakeRegion region = new FakeRegion(name, (int) size);
            regions.add(region);
            return region;
        }, () -> now);
    }

    private void connect(int width, int height) {
        surface.connected((generation, name) -> shown.add(generation + " " + name), PID);
        surface.frame(width, height, GL_4_6, 0);
    }

    private static int[] image(int width, int height, int... rows) {
        int[] pixels = new int[width * height];
        for (int row : rows) {
            Arrays.fill(pixels, row * width, (row + 1) * width, 0xff00ff00);
        }
        return pixels;
    }

    @Test
    void itNeedsTheHookAFramebufferAndGl3() {
        assertFalse(surface.isAvailable());
        assertFalse(surface.publish(new int[4], 2, 2, 0, 2));
        surface.connected((generation, name) -> { }, PID);
        assertFalse(surface.isAvailable(), "no framebuffer reported yet");
        surface.frame(4, 3, 2 << 16 | 1, 0);
        assertFalse(surface.isAvailable(), "GL 2.1 isn't drawn with");
        surface.frame(4, 3, GL_4_6, 0);
        assertTrue(surface.isAvailable());
        surface.frame(0, 0, GL_4_6, 0);
        assertFalse(surface.isAvailable(), "a minimized game has no framebuffer");
    }

    @Test
    void theFirstFrameLaysOutTheMappingAndTellsTheDll() {
        connect(4, 3);
        assertTrue(surface.publish(image(4, 3, 1), 4, 3, 1, 2));

        assertEquals(List.of("1 Local\\KrakenOverlay-1234-1"), shown);
        FakeRegion region = regions.get(0);
        assertEquals(GameSurface.OFFSET_PIXELS + 3 * 4 * 3 * 4, region.size);
        assertEquals(GameSurface.MAGIC, region.getInt(GameSurface.OFFSET_MAGIC));
        assertEquals(4, region.getInt(GameSurface.OFFSET_WIDTH));
        assertEquals(3, region.getInt(GameSurface.OFFSET_HEIGHT));
        assertEquals(3, region.getInt(GameSurface.OFFSET_SLOTS));
        assertEquals(-1, region.getInt(GameSurface.OFFSET_READING));
        assertEquals(1 << 4, region.getInt(GameSurface.OFFSET_PUBLISHED), "slot 0, sequence 1");
        assertEquals(1, region.getInt(GameSurface.OFFSET_SLOT_HEADERS), "top");
        assertEquals(2, region.getInt(GameSurface.OFFSET_SLOT_HEADERS + 4), "bottom");
        assertEquals(0, region.pixel(0, 0, 0, 4, 3), "row 0 isn't written");
        assertEquals(0xff00ff00, region.pixel(0, 3, 1, 4, 3));
    }

    @Test
    void eachFrameGoesToASlotNeitherPublishedNorRead() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        FakeRegion region = regions.get(0);
        region.putInt(GameSurface.OFFSET_READING, 1);
        surface.publish(image(4, 3, 2), 4, 3, 2, 3);
        assertEquals(2 | 2 << 4, region.getInt(GameSurface.OFFSET_PUBLISHED), "0 is published and 1 is read");
        region.putInt(GameSurface.OFFSET_READING, 0);
        surface.publish(image(4, 3, 1), 4, 3, 1, 2);
        assertEquals(1 | 3 << 4, region.getInt(GameSurface.OFFSET_PUBLISHED), "2 is published and 0 is read");
    }

    @Test
    void aSlotReusedClearsTheRowsItHeldBefore() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        FakeRegion region = regions.get(0);
        region.putInt(GameSurface.OFFSET_READING, 1);
        surface.publish(image(4, 3), 4, 3, 0, 0);
        region.putInt(GameSurface.OFFSET_READING, 2);
        surface.publish(image(4, 3, 2), 4, 3, 2, 3);
        assertEquals(0, region.getInt(GameSurface.OFFSET_PUBLISHED) & 0xf, "back to slot 0");
        assertEquals(0, region.pixel(0, 0, 0, 4, 3), "row 0 cleared: the slot held it, this frame doesn't");
        assertEquals(0xff00ff00, region.pixel(0, 0, 2, 4, 3));
        assertEquals(2, region.getInt(GameSurface.OFFSET_SLOT_HEADERS));
        assertEquals(3, region.getInt(GameSurface.OFFSET_SLOT_HEADERS + 4));
    }

    @Test
    void aNewSizeMakesANewGeneration() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        surface.frame(6, 2, GL_4_6, 1);
        surface.publish(image(6, 2, 1), 6, 2, 1, 2);
        assertEquals(List.of("1 Local\\KrakenOverlay-1234-1", "2 Local\\KrakenOverlay-1234-2"), shown);
        assertTrue(regions.get(0).closed, "the old mapping is closed");
        assertEquals(1 << 4, regions.get(1).getInt(GameSurface.OFFSET_PUBLISHED), "the new one starts over");
    }

    @Test
    void aSurfaceTheDllNeverDrawsIsGivenUp() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        now += 1_000_000_000L;
        assertTrue(surface.isAvailable(), "within the grace period");
        now += 2_000_000_000L;
        assertFalse(surface.isAvailable(), "not drawn after it");
        assertEquals("0 ", shown.get(shown.size() - 1), "the DLL is told to stop drawing");
        assertTrue(regions.get(0).closed);
        surface.frame(4, 3, GL_4_6, 1);
        assertFalse(surface.isAvailable(), "given up for the connection");
        connect(4, 3);
        assertTrue(surface.isAvailable(), "a new connection tries again");
    }

    @Test
    void aDrawnSurfaceStaysAvailable() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        surface.frame(4, 3, GL_4_6, 1);
        now += 10_000_000_000L;
        assertTrue(surface.isAvailable());
    }

    @Test
    void aDisconnectClosesTheMapping() {
        connect(4, 3);
        surface.publish(image(4, 3, 0), 4, 3, 0, 1);
        surface.disconnected();
        assertFalse(surface.isAvailable());
        assertTrue(regions.get(0).closed);
    }

    @Test
    void aPresentIsRecordedAndHeardUntilTheConnectionDrops() {
        List<Long> heard = new ArrayList<>();
        surface.setPresentListener(() -> heard.add(surface.getPresents()));
        connect(4, 3);
        assertEquals(0, surface.getPresents(), "none reported yet");
        surface.presented(41);
        surface.presented(43);
        assertEquals(List.of(41L, 43L), heard, "the listener runs after the count is recorded");
        surface.disconnected();
        assertEquals(0, surface.getPresents(), "the count belongs to the connection");
        surface.presented(50);
        connect(4, 3);
        assertEquals(0, surface.getPresents(), "a new connection starts with none");
    }

    @Test
    void freeSlotSkipsPublishedAndReading() {
        assertEquals(0, GameSurface.freeSlot(GameSurface.NONE_PUBLISHED, -1));
        assertEquals(1, GameSurface.freeSlot(0, -1));
        assertEquals(2, GameSurface.freeSlot(0 | 5 << 4, 1));
        assertEquals(0, GameSurface.freeSlot(1, 2));
    }

    /**
     * A heap buffer standing in for a shared mapping.
     */
    private static final class FakeRegion implements GameSurface.Region {

        final String name;
        final int size;
        final ByteBuffer buf;
        boolean closed;

        FakeRegion(String name, int size) {
            this.name = name;
            this.size = size;
            this.buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        }

        @Override
        public int getInt(long offset) {
            return buf.getInt((int) offset);
        }

        @Override
        public void putInt(long offset, int value) {
            buf.putInt((int) offset, value);
        }

        @Override
        public void write(long offset, int[] source, int start, int count) {
            for (int i = 0; i < count; i++) {
                buf.putInt((int) offset + i * 4, source[start + i]);
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        int pixel(int slot, int x, int y, int width, int height) {
            return buf.getInt(GameSurface.OFFSET_PIXELS + (slot * width * height + y * width + x) * 4);
        }
    }
}
