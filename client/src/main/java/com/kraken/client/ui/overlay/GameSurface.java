package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import lombok.extern.java.Log;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The overlay surface the native hook draws inside the game's own frame: a file mapping holding three frames of
 * premultiplied BGRA pixels, which {@link OverlayRenderer} fills and {@code kraken_hook.dll} uploads and draws over
 * the finished frame just before it is presented. Its layout and the lock-free handover are in
 * {@code docs/in-game-drawing.md} §2.4.
 *
 * <p>The hook's connection sets it up: {@link #connected} once the DLL reports it hooked the present, {@link #frame}
 * each time it reports the framebuffer's size, {@link #disconnected} when it drops. {@link #publish} runs on the poll
 * thread. The surface follows the framebuffer: a new size makes a new mapping (a new generation), which the DLL is told
 * to switch to.
 */
@Log(topic = "kraken")
@Singleton
public final class GameSurface {

    /**
     * "KOV1", the surface's magic.
     */
    static final int MAGIC = 0x31564f4b;
    static final int SLOTS = 3;
    static final int OFFSET_MAGIC = 0;
    static final int OFFSET_WIDTH = 4;
    static final int OFFSET_HEIGHT = 8;
    static final int OFFSET_SLOTS = 12;
    static final int OFFSET_PUBLISHED = 16;
    static final int OFFSET_READING = 20;
    static final int OFFSET_SLOT_HEADERS = 64;
    static final int SLOT_HEADER_SIZE = 16;
    static final int OFFSET_PIXELS = 128;

    /**
     * {@code published} before the first frame.
     */
    static final int NONE_PUBLISHED = 0xffffffff;

    /**
     * The lowest GL major version the DLL draws with.
     */
    private static final int MIN_GL_MAJOR = 3;

    /**
     * How long the DLL has to start drawing a new surface. One that isn't drawn by then was refused, or the DLL turned
     * the overlay off after a GL fault, and the client goes back to its own window.
     */
    private static final long ADOPT_GRACE_NANOS = 2_000_000_000L;

    /**
     * Tells the DLL which surface to draw.
     */
    @FunctionalInterface
    public interface Link {

        /**
         * Sends the SURFACE command.
         *
         * @param generation the surface's generation
         * @param name       the file mapping's name
         * @throws RuntimeException if the connection broke
         */
        void show(int generation, String name);
    }

    /**
     * Opens a region of shared memory.
     */
    @FunctionalInterface
    interface RegionFactory {

        /**
         * Creates a named region, zero-filled.
         *
         * @param name the file mapping's name
         * @param size its size in bytes
         * @return the region
         * @throws IllegalStateException if it can't be created
         */
        Region create(String name, long size);
    }

    /**
     * A region of shared memory, read and written at byte offsets.
     */
    interface Region {

        /**
         * Reads a 4-byte int.
         *
         * @param offset where
         * @return the int
         */
        int getInt(long offset);

        /**
         * Writes a 4-byte int.
         *
         * @param offset where
         * @param value  the int
         */
        void putInt(long offset, int value);

        /**
         * Copies ints in.
         *
         * @param offset where the first goes
         * @param source the ints
         * @param start  the first to copy
         * @param count  how many
         */
        void write(long offset, int[] source, int start, int count);

        /**
         * Unmaps and closes it.
         */
        void close();
    }

    private final RegionFactory factory;
    private final LongSupplier clock;

    private Link link;
    private long pid;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile int glVersion;
    private volatile int drawnGeneration;

    private Region region;
    private int width;
    private int height;
    private int generation;
    private int sequence;
    private long openedAt;
    private boolean refused;
    private final int[] tops = new int[SLOTS];
    private final int[] bottoms = new int[SLOTS];
    private boolean failing;

    @Inject
    GameSurface() {
        this(MappedRegion::create, System::nanoTime);
    }

    /**
     * A surface over regions from a factory, for tests.
     *
     * @param factory makes the regions
     * @param clock   the time in nanoseconds
     */
    GameSurface(RegionFactory factory, LongSupplier clock) {
        this.factory = factory;
        this.clock = clock;
    }

    /**
     * Whether the DLL draws the surface now: it hooked the present, reported a framebuffer, has a GL it draws with,
     * and took the last surface it was given.
     *
     * @return true while {@link #publish} reaches the game
     */
    public synchronized boolean isAvailable() {
        if (link == null || refused || frameWidth <= 0 || frameHeight <= 0 || (glVersion >>> 16) < MIN_GL_MAJOR) {
            return false;
        }
        if (region != null && drawnGeneration != generation && clock.getAsLong() - openedAt > ADOPT_GRACE_NANOS) {
            refused = true;
            log.warning("The native hook isn't drawing overlay surface " + generation + "; overlays go back to the "
                    + "window over the game.");
            stopDrawing();
            return false;
        }
        return true;
    }

    /**
     * The game's framebuffer width, which a frame should match.
     *
     * @return the width in device pixels, 0 before the DLL reports one
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * The game's framebuffer height.
     *
     * @return the height in device pixels, 0 before the DLL reports one
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * The surface generation the DLL last said it draws.
     *
     * @return the generation, 0 for none
     */
    public int getDrawnGeneration() {
        return drawnGeneration;
    }

    /**
     * The hook drew its present and can take the surface. Called on the hook's reader thread.
     *
     * @param link tells the DLL which surface to draw
     * @param pid  the game's process id, which names the mappings
     */
    public synchronized void connected(Link link, long pid) {
        this.link = link;
        this.pid = pid;
        refused = false;
        closeRegion();
    }

    /**
     * The DLL's report of its framebuffer and what it draws. Called on the hook's reader thread.
     *
     * @param width      the framebuffer's width in device pixels
     * @param height     its height
     * @param glVersion  the GL version, {@code major << 16 | minor}
     * @param generation the surface generation it draws, 0 for none
     */
    public void frame(int width, int height, int glVersion, int generation) {
        this.frameWidth = width;
        this.frameHeight = height;
        this.glVersion = glVersion;
        this.drawnGeneration = generation;
    }

    /**
     * The hook's connection dropped; the DLL stops drawing on its own. Called on the hook's reader thread.
     */
    public synchronized void disconnected() {
        link = null;
        frameWidth = 0;
        frameHeight = 0;
        drawnGeneration = 0;
        closeRegion();
    }

    /**
     * Hands the DLL a frame. Only the rows that changed are copied: this frame's rows that hold pixels, and the rows
     * the slot written into held before, which this frame clears. Poll thread.
     *
     * @param pixels premultiplied ARGB, rows top to bottom, {@code width * height} of them
     * @param width  the frame's width, which should be {@link #getFrameWidth()}
     * @param height the frame's height
     * @param top    the first row holding a pixel
     * @param bottom one past the last row holding a pixel ({@code top} for an empty frame)
     * @return true if the frame went to the game
     */
    public synchronized boolean publish(int[] pixels, int width, int height, int top, int bottom) {
        if (!isAvailable() || width < 1 || height < 1 || pixels.length < width * height) {
            return false;
        }
        top = Math.max(0, Math.min(top, height));
        bottom = Math.max(top, Math.min(bottom, height));
        try {
            if (region == null || width != this.width || height != this.height) {
                open(width, height);
            }
            int published = region.getInt(OFFSET_PUBLISHED);
            int reading = region.getInt(OFFSET_READING);
            int slot = freeSlot(published, reading);
            int from = Math.min(top, tops[slot]);
            int to = Math.max(bottom, bottoms[slot]);
            if (bottoms[slot] <= tops[slot]) {
                from = top;
                to = bottom;
            }
            if (to > from) {
                long base = OFFSET_PIXELS + (long) slot * width * height * 4;
                region.write(base + (long) from * width * 4, pixels, from * width, (to - from) * width);
            }
            long header = OFFSET_SLOT_HEADERS + (long) slot * SLOT_HEADER_SIZE;
            region.putInt(header, top);
            region.putInt(header + 4, bottom);
            tops[slot] = top;
            bottoms[slot] = bottom;
            sequence = (sequence + 1) & 0x0fffffff;
            region.putInt(OFFSET_PUBLISHED, slot | sequence << 4);
            // The DLL stores which slot it reads, then re-reads published; this fence keeps the next read of reading
            // after the store above, so the slot chosen next is never one the DLL confirmed
            VarHandle.fullFence();
            failing = false;
            return true;
        } catch (RuntimeException e) {
            if (!failing) {
                failing = true;
                log.log(Level.WARNING, "Could not hand the overlay to the game", e);
            }
            return false;
        }
    }

    /**
     * The slot to write: neither the one last published nor the one the DLL reads.
     *
     * @param published the published word
     * @param reading   the slot the DLL reads, or -1
     * @return the slot
     */
    static int freeSlot(int published, int reading) {
        int last = published == NONE_PUBLISHED ? -1 : published & 0xf;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slot != last && slot != reading) {
                return slot;
            }
        }
        throw new IllegalStateException("no free slot: published " + last + ", reading " + reading);
    }

    /**
     * Makes a mapping for a new size and tells the DLL to draw it. The old one is closed once the DLL has the new
     * name; the DLL keeps its own view of the old until it switches.
     *
     * @param width  the frame's width
     * @param height its height
     */
    private void open(int width, int height) {
        int next = generation + 1;
        String name = "Local\\KrakenOverlay-" + pid + "-" + next;
        long size = OFFSET_PIXELS + (long) SLOTS * width * height * 4;
        Region created = factory.create(name, size);
        created.putInt(OFFSET_MAGIC, MAGIC);
        created.putInt(OFFSET_WIDTH, width);
        created.putInt(OFFSET_HEIGHT, height);
        created.putInt(OFFSET_SLOTS, SLOTS);
        created.putInt(OFFSET_PUBLISHED, NONE_PUBLISHED);
        created.putInt(OFFSET_READING, -1);
        try {
            link.show(next, name);
        } catch (RuntimeException e) {
            created.close();
            throw e;
        }
        closeRegion();
        region = created;
        generation = next;
        openedAt = clock.getAsLong();
        this.width = width;
        this.height = height;
        sequence = 0;
        Arrays.fill(tops, 0);
        Arrays.fill(bottoms, 0);
    }

    /**
     * Tells the DLL to stop drawing any surface, so it doesn't keep showing a last frame nothing updates, and closes
     * this side's mapping.
     */
    private void stopDrawing() {
        try {
            link.show(0, "");
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not tell the native hook to stop drawing", e);
        }
        closeRegion();
    }

    private void closeRegion() {
        if (region != null) {
            region.close();
            region = null;
        }
    }

    /**
     * A page-file-backed file mapping, through Win32.
     */
    private static final class MappedRegion implements Region {

        private final WinNT.HANDLE handle;
        private final Pointer view;

        private MappedRegion(WinNT.HANDLE handle, Pointer view) {
            this.handle = handle;
            this.view = view;
        }

        static Region create(String name, long size) {
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFileMapping(WinBase.INVALID_HANDLE_VALUE, null,
                    WinNT.PAGE_READWRITE, (int) (size >>> 32), (int) size, name);
            if (handle == null) {
                throw new IllegalStateException("CreateFileMapping " + name + " failed: "
                        + Kernel32.INSTANCE.GetLastError());
            }
            Pointer view = Kernel32.INSTANCE.MapViewOfFile(handle, WinNT.SECTION_MAP_READ | WinNT.SECTION_MAP_WRITE,
                    0, 0, (int) size);
            if (view == null) {
                int error = Kernel32.INSTANCE.GetLastError();
                Kernel32.INSTANCE.CloseHandle(handle);
                throw new IllegalStateException("MapViewOfFile " + name + " failed: " + error);
            }
            return new MappedRegion(handle, view);
        }

        @Override
        public int getInt(long offset) {
            return view.getInt(offset);
        }

        @Override
        public void putInt(long offset, int value) {
            view.setInt(offset, value);
        }

        @Override
        public void write(long offset, int[] source, int start, int count) {
            view.write(offset, source, start, count);
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.UnmapViewOfFile(view);
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }
}
