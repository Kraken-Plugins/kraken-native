package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;
import com.kraken.client.FrameRates;
import com.kraken.client.KrakenConfig;
import com.kraken.client.ProcessAttacher;
import lombok.extern.java.Log;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import javax.swing.*;

/**
 * Draws the plugins' {@link Overlay}s over the game. The client creates it at launch.
 *
 * <p>Two ways to the screen. While the native hook draws the {@link GameSurface}, each frame goes to the game, which
 * draws it inside its own frame just before presenting it, at the framebuffer's size. Otherwise frames go through the
 * {@link GameOverlay} window, alongside the dev tools' overlays.
 *
 * <ul>
 *   <li><b>On the client thread.</b> At the end of each API poll, if overlays are added and a frame is due, the poll
 *       thread clears an image and draws every overlay into it ({@link OverlayCompositor}). Overlays' {@code render}
 *       thus runs where game events and {@code ClientThread} tasks do, and memory reads stay off the EDT.
 *   <li><b>When a frame is due.</b> For the game, once per game frame, when the DLL asks
 *       ({@link GameSurface#drawRequested}), which wakes the poll loop at once. With frame sync on (the
 *       {@linkplain KrakenConfig#syncOverlays setting}, while overlays are added), the DLL asks at the end of each game
 *       frame and holds the game until the frame is drawn ({@link GameSurface#finish}), so the overlay is drawn from
 *       the same state as the frame it is shown with. Otherwise it asks as the game presents, and the
 *       {@linkplain KrakenConfig#overlayFrameRate frame rate} caps the frames. For the window, which nothing paces, at
 *       the frame rate, at most {@value #WINDOW_MAX_FRAME_RATE} a second (one a poll).
 *   <li><b>Two images for the window.</b> The EDT paints the last finished frame while the next one is drawn into the
 *       other, so it never shows a half-drawn frame. Each image is locked while it's drawn or painted, for the rare
 *       frame where the EDT is still painting an image when its turn to be drawn into comes round.
 *   <li><b>Canvas pixels.</b> The image is sized in device pixels, so text stays sharp on scaled displays, and the
 *       graphics handed to overlays is scaled so they draw in the client's canvas pixels, where {@code Perspective}
 *       results land.
 * </ul>
 *
 * <p>For the game, a frame is drawn into one image the framebuffer's size, and only the rows that hold pixels (and the
 * rows the last frame held, which it clears) are cleared and handed over, so a sparse overlay costs little.
 *
 * <p>Overlays start each frame with the font, size and panel background the settings give ({@link KrakenConfig}), and
 * each frame handed over is counted in {@link FrameRates}. After each frame the {@link OverlayEditor} hears where the
 * overlays went, so the user can drag them with Alt held, and while they do, the frames outline them.
 *
 * <p>Nothing is read or drawn while no overlay is added or the game area isn't showing.
 */
@Log(topic = "kraken")
@Singleton
public final class OverlayRenderer {

    /**
     * The fastest frame rate for the game, whatever the settings file holds.
     */
    static final int MAX_FRAME_RATE = 240;

    /**
     * The fastest frame rate for the window: the API polls about every 20 ms, and a frame is drawn at the end of a
     * poll.
     */
    static final int WINDOW_MAX_FRAME_RATE = 50;

    /**
     * The bounds on the overlay font size, whatever the settings file holds.
     */
    private static final int MIN_FONT_SIZE = 6;
    private static final int MAX_FONT_SIZE = 72;

    private final OverlayManager manager;
    private final GameOverlay window;
    private final GameSurface surface;
    private final OverlayEditor editor;
    private final ProcessAttacher attacher;
    private final KrakenConfig config;
    private final FrameRates frameRates;
    private final OverlayCompositor compositor;
    private volatile Frame front;
    private Frame back;
    private final FrameSchedule schedule = new FrameSchedule();
    private boolean failing;

    /**
     * The font and size the compositor was last given. Poll thread only.
     */
    private OverlayFont font;
    private int fontSize;

    /**
     * The image frames for the game are drawn into, and the rows its last frame held. Poll thread only.
     */
    private BufferedImage gameImage;
    private int gameTop;
    private int gameBottom;

    /**
     * Whether the game was handed a frame with pixels that a later one hasn't cleared yet. Poll thread only.
     */
    private boolean gameShowing;

    /**
     * The frame of the DLL's request the last frame for the game was drawn for. Poll thread only.
     */
    private long drawnFrame;

    @Inject
    OverlayRenderer(OverlayManager manager, GameOverlay window, GameSurface surface, OverlayEditor editor,
                    ProcessAttacher attacher, KrakenConfig config, FrameRates frameRates) {
        this.manager = manager;
        this.window = window;
        this.surface = surface;
        this.editor = editor;
        this.attacher = attacher;
        this.config = config;
        this.frameRates = frameRates;
        Font font = UIManager.getFont("Label.font");
        this.compositor = new OverlayCompositor(font, font.deriveFont(Font.BOLD));
        window.addPainter(new OverlayPainter() {
            @Override
            public boolean isActive() {
                // Not just the attacher's state: once the game exits, the poll loop stops and the last frame stays
                GameSession session = attacher.attached();
                return front != null && session != null && session.isAttached();
            }

            @Override
            public void paint(OverlayContext overlay) {
                Frame frame = front;
                if (frame != null) {
                    synchronized (frame) {
                        overlay.getGraphics().drawImage(frame.image, 0, 0, overlay.getWidth(), overlay.getHeight(),
                                null);
                    }
                }
            }
        });
        attacher.addFrameListener(this::frame);
        surface.setDrawListener(this::drawRequested);
    }

    /**
     * Wakes the client thread to draw the frame the DLL asks for: always when the game waits for it, so it can go on,
     * otherwise only if there is something to draw. Hook's reader thread.
     */
    private void drawRequested() {
        GameSession session = attacher.attached();
        GameSurface.DrawRequest request = surface.getDrawRequest();
        if (session != null && (!manager.isEmpty() || request != null && request.waits())) {
            session.requestFrame();
        }
    }

    /**
     * Draws a frame if one is due, then lets a game waiting for the frame it was asked for go on, drawn or not. Poll
     * thread.
     *
     * @param session the attached API
     */
    private void frame(GameSession session) {
        GameSurface.DrawRequest request = surface.getDrawRequest();
        try {
            draw(session, request);
            failing = false;
        } catch (RuntimeException | LinkageError e) {
            if (!failing) {
                failing = true;
                log.log(Level.WARNING, "Drawing the overlays failed", e);
            }
        } finally {
            surface.finish(request);
        }
    }

    private void draw(GameSession session, GameSurface.DrawRequest request) {
        List<Overlay> overlays = manager.getOverlays();
        if (surface.isAvailable()) {
            clear();
            surface.setSync(config.syncOverlays() && !overlays.isEmpty());
            drawInGame(session, overlays, request);
            return;
        }
        surface.setSync(false);
        gameShowing = false;
        GameOverlay.Surface area = window.getSurface();
        if (overlays.isEmpty() || area == null) {
            clear();
            editor.nothingDrawn();
            return;
        }
        if (!due(front == null, WINDOW_MAX_FRAME_RATE)) {
            return;
        }
        int canvasWidth = session.client().getCanvasWidth();
        int canvasHeight = session.client().getCanvasHeight();
        if (canvasWidth < 1 || canvasHeight < 1) {
            clear();
            editor.nothingDrawn();
            return;
        }
        int width = area.deviceWidth();
        int height = area.deviceHeight();
        applyStyle();
        Frame frame = back != null && back.fits(width, height) ? back : new Frame(width, height);
        synchronized (frame) {
            Graphics2D g = frame.image.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, width, height);
                g.setComposite(AlphaComposite.SrcOver);
                g.scale(width / (double) canvasWidth, height / (double) canvasHeight);
                compose(g, overlays, canvasWidth, canvasHeight);
            } finally {
                g.dispose();
            }
        }
        back = front;
        front = frame;
        window.refresh();
        frameRates.overlayFrameDrawn();
    }

    /**
     * Draws the overlays, outlined if the user is moving them, and tells the editor where the movable ones went. Poll
     * thread.
     *
     * @param g            where to draw, in canvas pixels
     * @param overlays     the overlays, in drawing order
     * @param canvasWidth  the canvas's width
     * @param canvasHeight the canvas's height
     */
    private void compose(Graphics2D g, List<Overlay> overlays, int canvasWidth, int canvasHeight) {
        compositor.setEditing(editor.isEditing(), editor.getTarget(), editor.isResizing());
        compositor.render(g, overlays, canvasWidth, canvasHeight);
        editor.frameDrawn(overlays, canvasWidth, canvasHeight);
    }

    /**
     * Gives the compositor the settings' font, size and panel background. Poll thread.
     */
    private void applyStyle() {
        OverlayFont nextFont = config.overlayFont();
        int nextSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, config.overlayFontSize()));
        if (nextFont != null && (nextFont != font || nextSize != fontSize)) {
            font = nextFont;
            fontSize = nextSize;
            compositor.setFonts(font.panel(fontSize), font.dynamic(fontSize));
        }
        Color background = config.overlayBackground();
        if (background != null) {
            compositor.setBackground(background);
        }
    }

    /**
     * Whether a frame is due at the {@linkplain KrakenConfig#overlayFrameRate frame rate}, moving the schedule on if so.
     *
     * @param restart true when no frame is on screen yet, so one is due at once and the schedule starts over
     * @param maxRate the fastest rate the target takes, whatever the settings ask
     * @return true to draw a frame now
     */
    private boolean due(boolean restart, int maxRate) {
        long interval = 1_000_000_000L / Math.max(1, Math.min(maxRate, config.overlayFrameRate()));
        return schedule.due(System.nanoTime(), interval, restart);
    }

    /**
     * Whether a frame for the game is due, moving the schedule on if so. Once the DLL asks for frames, one is due for
     * each request since the last frame drawn: always for a game frame waiting on it, otherwise at most at the frame
     * rate. The game shows only the frame drawn for it (with frame sync) or the newest one, so drawing more often would
     * waste frames, and drawing on a timer instead would leave some game frames showing an older one. Before the first
     * request, one is due at the frame rate.
     *
     * @param fresh   true for a new image, which is drawn at once
     * @param request the DLL's latest request, or null
     * @return true to draw a frame now
     */
    private boolean gameFrameDue(boolean fresh, GameSurface.DrawRequest request) {
        if (!fresh && request != null && request.frame() == drawnFrame) {
            return false;
        }
        if (request != null && request.waits()) {
            drawnFrame = request.frame();
            return true;
        }
        if (!due(fresh, MAX_FRAME_RATE)) {
            return false;
        }
        drawnFrame = request == null ? 0 : request.frame();
        return true;
    }

    /**
     * Draws a frame for the game if one is due, at the framebuffer's size, and hands it to the {@link GameSurface}. With
     * no overlays, hands over one empty frame to clear the last, then reads and draws nothing.
     *
     * @param session  the attached API
     * @param overlays the overlays, in drawing order
     * @param request  the DLL's latest request for a frame, or null
     */
    private void drawInGame(GameSession session, List<Overlay> overlays, GameSurface.DrawRequest request) {
        if (overlays.isEmpty()) {
            clearGame();
            return;
        }
        int canvasWidth = session.client().getCanvasWidth();
        int canvasHeight = session.client().getCanvasHeight();
        if (canvasWidth < 1 || canvasHeight < 1) {
            clearGame();
            return;
        }
        int width = surface.getFrameWidth();
        int height = surface.getFrameHeight();
        boolean fresh = gameImage == null || gameImage.getWidth() != width || gameImage.getHeight() != height;
        if (!gameFrameDue(fresh, request)) {
            return;
        }
        if (fresh) {
            gameImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            gameTop = 0;
            gameBottom = 0;
        } else {
            clearRows(gameImage, gameTop, gameBottom);
        }
        applyStyle();
        Graphics2D g = gameImage.createGraphics();
        try {
            g.scale(width / (double) canvasWidth, height / (double) canvasHeight);
            compose(g, overlays, canvasWidth, canvasHeight);
        } finally {
            g.dispose();
        }
        int[] pixels = pixels(gameImage);
        int top = firstRow(pixels, width, height);
        int bottom = top == height ? top : lastRow(pixels, width, top, height) + 1;
        if (surface.publish(pixels, width, height, top, bottom, drawnFrame)) {
            gameTop = top;
            gameBottom = bottom;
            gameShowing = bottom > top;
            frameRates.overlayFrameDrawn();
        } else {
            // The surface went away mid-frame: clear the whole image, since the rows drawn weren't recorded
            gameTop = 0;
            gameBottom = height;
        }
    }

    /**
     * Hands the game one empty frame to clear the last frame's pixels, if it still shows them. Poll thread.
     */
    private void clearGame() {
        editor.nothingDrawn();
        if (gameShowing && gameImage != null) {
            clearRows(gameImage, gameTop, gameBottom);
            gameTop = 0;
            gameBottom = 0;
            gameShowing = !surface.publish(pixels(gameImage), gameImage.getWidth(), gameImage.getHeight(), 0, 0, 0);
        }
    }

    /**
     * The pixels behind an image of {@link BufferedImage#TYPE_INT_ARGB_PRE}.
     *
     * @param image the image
     * @return its pixel array, rows top to bottom
     */
    private static int[] pixels(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    /**
     * Clears rows of an image to transparent.
     *
     * @param image  the image
     * @param top    the first row
     * @param bottom one past the last
     */
    static void clearRows(BufferedImage image, int top, int bottom) {
        int width = image.getWidth();
        if (bottom > top) {
            Arrays.fill(pixels(image), top * width, bottom * width, 0);
        }
    }

    /**
     * The first row with a pixel that isn't fully transparent.
     *
     * @param pixels the pixels
     * @param width  the row length
     * @param height the row count
     * @return the row, or {@code height} if there is none
     */
    static int firstRow(int[] pixels, int width, int height) {
        int[] zeros = new int[width];
        for (int row = 0; row < height; row++) {
            if (!blank(pixels, row * width, zeros)) {
                return row;
            }
        }
        return height;
    }

    /**
     * The last row with a pixel, searching up from the bottom to a row known to hold one.
     *
     * @param pixels the pixels
     * @param width  the row length
     * @param top    a row that holds a pixel
     * @param height the row count
     * @return the row
     */
    static int lastRow(int[] pixels, int width, int top, int height) {
        int[] zeros = new int[width];
        for (int row = height - 1; row > top; row--) {
            if (!blank(pixels, row * width, zeros)) {
                return row;
            }
        }
        return top;
    }

    /**
     * Whether a row is fully transparent. It is compared with a row of zeros by {@link Arrays#mismatch}, which the JDK
     * runs several pixels at a time, since the empty rows of a sparse overlay are most of the frame.
     *
     * @param pixels the pixels
     * @param start  the row's first pixel
     * @param zeros  a row of zeros, as long as the row
     * @return true if every pixel is 0
     */
    private static boolean blank(int[] pixels, int start, int[] zeros) {
        return Arrays.mismatch(pixels, start, start + zeros.length, zeros, 0, zeros.length) < 0;
    }

    /**
     * Drops the last frame, so the overlay window stops showing it.
     */
    private void clear() {
        if (front != null) {
            front = null;
            back = null;
            window.refresh();
        }
    }

    /**
     * When frames are due: at most one an interval. A frame that is due moves the schedule on by one interval, so the
     * average rate holds on a poll that doesn't divide the interval, and a call that isn't due leaves it alone, so
     * however often it is asked, the schedule never runs ahead of the clock.
     */
    static final class FrameSchedule {

        private long next;
        private boolean started;

        /**
         * Whether a frame is due now, moving the schedule on if so.
         *
         * @param now      the time, in nanoseconds
         * @param interval the nanoseconds between frames
         * @param restart  true to draw at once and start the schedule over
         * @return true to draw a frame now
         */
        boolean due(long now, long interval, boolean restart) {
            if (!restart && started && now - next < 0) {
                return false;
            }
            // Step the schedule rather than restarting it, so 30 fps on a 20 ms poll averages 30, not 25; start it over
            // after a gap of more than a frame, so frames don't bunch up after a pause
            next = restart || !started || now - next > interval ? now + interval : next + interval;
            started = true;
            return true;
        }
    }

    /**
     * One frame's image.
     */
    private static final class Frame {
        private final BufferedImage image;

        Frame(int width, int height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        boolean fits(int width, int height) {
            return image.getWidth() == width && image.getHeight() == height;
        }
    }
}
