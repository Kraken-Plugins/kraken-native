package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The overlay surface the native hook draws inside the game's own frame: a file mapping holding three frames of
 * premultiplied BGRA pixels, which {@link OverlayRenderer} fills and {@code kraken_hook.dll} uploads and draws over
 * the finished frame just before it is presented. Its layout and the lock-free handover are in
 * {@code docs/developer/in-game-drawing.md} ("The Overlay Surface").
 *
 * <p>The hook's connection sets it up: {@link #connected} once the DLL reports it hooked the present, {@link #frame}
 * each time it reports the framebuffer's size, {@link #drawRequested} each time it asks for an overlay frame,
 * {@link #disconnected} when it drops. {@link #publish} runs on the poll thread. The surface follows the framebuffer: a
 * new size makes a new mapping (a new generation), which the DLL is told to switch to.
 *
 * <p>Frame sync ({@code docs/developer/in-game-drawing.md}, "Frame Sync"): while it is on ({@link #setSync}), the DLL
 * holds the game at the end of each frame and asks for that frame's overlay with a tag. The frame drawn for it goes
 * into a slot carrying the tag, which the DLL shows when it presents that game frame, and {@link #finish} lets the game
 * go on.
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
    static final int OFFSET_SLOT_TAG = 8;
    static final int OFFSET_SLOT_SEQ = 12;
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
     * How many times a frame tries for a slot the DLL isn't reading before it gives up.
     */
    private static final int CLAIM_ATTEMPTS = 8;

    /**
     * The DLL's connection: which surface to draw, and frame sync.
     */
    public interface Link {

        /**
         * Sends the SURFACE command.
         *
         * @param generation the surface's generation
         * @param name       the file mapping's name
         * @throws RuntimeException if the connection broke
         */
        void show(int generation, String name);

        /**
         * Sends the FRAME_SYNC command.
         *
         * @param on whether the game should wait for each frame's overlay
         * @throws RuntimeException if the connection broke
         */
        void sync(boolean on);

        /**
         * Sends the FRAME_DONE command.
         *
         * @param frame the frame whose overlay is in the surface
         * @throws RuntimeException if the connection broke
         */
        void done(long frame);
    }

    /**
     * The DLL's request for an overlay frame.
     *
     * @param frame the frame: with frame sync, the tag of the game frame being queued; otherwise the present count
     * @param waits whether the game waits for it, until {@link #finish}
     */
    public record DrawRequest(long frame, boolean waits) {
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
    /**
     * -- GETTER --
     *  The game's framebuffer width, which a frame should match.
     *
     * @return the width in device pixels, 0 before the DLL reports one
     */
    @Getter
    private volatile int frameWidth;
    /**
     * -- GETTER --
     *  The game's framebuffer height.
     *
     * @return the height in device pixels, 0 before the DLL reports one
     */
    @Getter
    private volatile int frameHeight;
    private volatile int glVersion;
    /**
     * -- GETTER --
     *  The surface generation the DLL last said it draws.
     *
     * @return the generation, 0 for none
     */
    @Getter
    private volatile int drawnGeneration;
    /**
     * -- GETTER --
     *  The DLL's latest request for an overlay frame on this connection.
     *
     * @return the request, or null before the first
     */
    @Getter
    private volatile DrawRequest drawRequest;
    /**
     * -- SETTER --
     *  Sets what runs each time the DLL asks for an overlay frame: on the hook's reader thread, so it should only hand
     *  work to another thread.
     *
     * @param listener the listener, or null for none
     */
    @Setter
    private volatile Runnable drawListener;

    /**
     * Whether the connected DLL can hold the game for each frame's overlay, and whether it was last asked to.
     */
    private boolean frameSync;
    private boolean syncing;

    /**
     * The last frame {@link #finish} let the game go on from.
     */
    private long finished;

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
     * The DLL's request for an overlay frame. Called on the hook's reader thread.
     *
     * @param frame the frame: with frame sync, the tag of the game frame being queued; otherwise the present count
     * @param waits whether the game waits for it
     */
    public void drawRequested(long frame, boolean waits) {
        drawRequest = new DrawRequest(frame, waits);
        Runnable listener = drawListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * The hook drew its present and can take the surface. Called on the hook's reader thread.
     *
     * @param link      tells the DLL which surface to draw, and frame sync
     * @param pid       the game's process id, which names the mappings
     * @param frameSync whether the DLL can hold the game for each frame's overlay
     */
    public synchronized void connected(Link link, long pid, boolean frameSync) {
        this.link = link;
        this.pid = pid;
        this.frameSync = frameSync;
        refused = false;
        syncing = false;
        drawRequest = null;
        finished = 0;
        closeRegion();
    }

    /**
     * Asks the DLL to hold the game for each frame's overlay, or to stop. Does nothing if it already is (or isn't), or
     * if the DLL can't. Poll thread.
     *
     * @param on whether the game should wait for each frame's overlay
     */
    public synchronized void setSync(boolean on) {
        boolean next = on && frameSync && link != null;
        if (next == syncing) {
            return;
        }
        try {
            link.sync(next);
            syncing = next;
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not change frame sync", e);
        }
    }

    /**
     * Whether the DLL holds the game for each frame's overlay.
     *
     * @return true while frame sync is on
     */
    public synchronized boolean isSyncing() {
        return syncing;
    }

    /**
     * Lets a game waiting for a frame's overlay go on, whether or not one was drawn for it. Does nothing for a request
     * nothing waits for, or one already finished. Poll thread.
     *
     * @param request the request the frame was drawn for, or null
     */
    public synchronized void finish(DrawRequest request) {
        if (request == null || !request.waits() || request.frame() == finished || link == null) {
            return;
        }
        finished = request.frame();
        try {
            link.done(request.frame());
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not tell the native hook a frame is done", e);
        }
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
        frameSync = false;
        syncing = false;
        drawRequest = null;
        closeRegion();
    }

    /**
     * Hands the DLL a frame. Only the rows that changed are copied: this frame's rows that hold pixels, and the rows
     * the slot written into held before, which this frame clears. The slot keeps the frame's tag, so the DLL can show
     * it with the game frame it was drawn for. Poll thread.
     *
     * @param pixels premultiplied ARGB, rows top to bottom, {@code width * height} of them
     * @param width  the frame's width, which should be {@link #getFrameWidth()}
     * @param height the frame's height
     * @param top    the first row holding a pixel
     * @param bottom one past the last row holding a pixel ({@code top} for an empty frame)
     * @param tag    the frame of the request it was drawn for, 0 for none
     * @return true if the frame went to the game
     */
    public synchronized boolean publish(int[] pixels, int width, int height, int top, int bottom, long tag) {
        if (!isAvailable() || width < 1 || height < 1 || pixels.length < width * height) {
            return false;
        }
        top = Math.clamp(top, 0, height);
        bottom = Math.clamp(bottom, top, height);
        try {
            if (region == null || width != this.width || height != this.height) {
                open(width, height);
            }
            int published = region.getInt(OFFSET_PUBLISHED);
            int slot = claimSlot(published);
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
            sequence = (sequence + 1) & 0x0fffffff;
            region.putInt(header, top);
            region.putInt(header + 4, bottom);
            region.putInt(header + OFFSET_SLOT_SEQ, sequence);
            region.putInt(header + OFFSET_SLOT_TAG, (int) tag);
            tops[slot] = top;
            bottoms[slot] = bottom;
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
     * Picks the slot to write and clears its tag: neither the one last published nor the one the DLL reads. The DLL
     * stores which slot it reads, then checks the slot still holds what it wants (the tag, or that it is still the
     * published one); this clears the tag, then checks the slot the DLL reads again, each with a fence between. So
     * either this sees the DLL reading the slot and picks another, or the DLL sees the cleared tag and leaves it.
     *
     * @param published the published word
     * @return the slot, which no longer carries a tag
     * @throws IllegalStateException if the DLL moved to each slot this picked
     */
    private int claimSlot(int published) {
        int avoid = -1;
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            int slot = freeSlot(published, region.getInt(OFFSET_READING), avoid);
            region.putInt(OFFSET_SLOT_HEADERS + (long) slot * SLOT_HEADER_SIZE + OFFSET_SLOT_TAG, 0);
            VarHandle.fullFence();
            if (region.getInt(OFFSET_READING) != slot) {
                return slot;
            }
            avoid = slot;
        }
        throw new IllegalStateException("the native hook kept reading the slot picked to write");
    }

    /**
     * The slot to write: neither the one last published, nor the one the DLL reads, nor one to avoid.
     *
     * @param published the published word
     * @param reading   the slot the DLL reads, or -1
     * @param avoid     a slot the DLL was found reading, or -1
     * @return the slot
     */
    static int freeSlot(int published, int reading, int avoid) {
        int last = published == NONE_PUBLISHED ? -1 : published & 0xf;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slot != last && slot != reading && slot != avoid) {
                return slot;
            }
        }
        throw new IllegalStateException("no free slot: published " + last + ", reading " + reading + ", avoiding "
                + avoid);
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
