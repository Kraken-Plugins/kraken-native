package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.GameSession;
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
 *   <li><b>On the client thread.</b> At the end of each API poll, if overlays are added and a frame is due (the
 *       {@linkplain KrakenConfig#overlayFrameRate frame rate}, 30 a second by default), the poll thread clears an
 *       image, draws every overlay into it ({@link OverlayCompositor}) and hands it to the EDT. Overlays'
 *       {@code render} thus runs where game events and {@code ClientThread} tasks do, and memory reads stay off the
 *       EDT.
 *   <li><b>Two images.</b> The EDT paints the last finished frame while the next one is drawn into the other, so it
 *       never shows a half-drawn frame. Each image is locked while it's drawn or painted, for the rare frame where the
 *       EDT is still painting an image when its turn to be drawn into comes round.
 *   <li><b>Canvas pixels.</b> The image is sized in device pixels, so text stays sharp on scaled displays, and the
 *       graphics handed to overlays is scaled so they draw in the client's canvas pixels, where {@code Perspective}
 *       results land.
 * </ul>
 *
 * <p>For the game, a frame is drawn into one image the framebuffer's size, and only the rows that hold pixels (and the
 * rows the last frame held, which it clears) are cleared and handed over, so a sparse overlay costs little.
 *
 * <p>Nothing is read or drawn while no overlay is added or the game area isn't showing.
 */
@Log(topic = "kraken")
@Singleton
public final class OverlayRenderer {

    /**
     * The fastest frame rate: the API polls about every 20 ms, and a frame is drawn at the end of a poll.
     */
    private static final int MAX_FRAME_RATE = 50;

    private final OverlayManager manager;
    private final GameOverlay window;
    private final GameSurface surface;
    private final KrakenConfig config;
    private final OverlayCompositor compositor;
    private volatile Frame front;
    private Frame back;
    private long nextFrame = System.nanoTime();
    private boolean failing;

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

    @Inject
    OverlayRenderer(OverlayManager manager, GameOverlay window, GameSurface surface, ProcessAttacher attacher,
                    KrakenConfig config) {
        this.manager = manager;
        this.window = window;
        this.surface = surface;
        this.config = config;
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
    }

    /**
     * Draws a frame if one is due. Poll thread.
     *
     * @param session the attached API
     */
    private void frame(GameSession session) {
        try {
            draw(session);
            failing = false;
        } catch (RuntimeException | LinkageError e) {
            if (!failing) {
                failing = true;
                log.log(Level.WARNING, "Drawing the overlays failed", e);
            }
        }
    }

    private void draw(GameSession session) {
        List<Overlay> overlays = manager.getOverlays();
        if (surface.isAvailable()) {
            clear();
            drawInGame(session, overlays);
            return;
        }
        gameShowing = false;
        GameOverlay.Surface area = window.getSurface();
        if (overlays.isEmpty() || area == null) {
            clear();
            return;
        }
        if (!due(front != null)) {
            return;
        }
        int canvasWidth = session.client().getCanvasWidth();
        int canvasHeight = session.client().getCanvasHeight();
        if (canvasWidth < 1 || canvasHeight < 1) {
            clear();
            return;
        }
        int width = area.deviceWidth();
        int height = area.deviceHeight();
        Frame frame = back != null && back.fits(width, height) ? back : new Frame(width, height);
        synchronized (frame) {
            Graphics2D g = frame.image.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, width, height);
                g.setComposite(AlphaComposite.SrcOver);
                g.scale(width / (double) canvasWidth, height / (double) canvasHeight);
                compositor.render(g, overlays, canvasWidth, canvasHeight);
            } finally {
                g.dispose();
            }
        }
        back = front;
        front = frame;
        window.refresh();
    }

    /**
     * Whether a frame is due at the {@linkplain KrakenConfig#overlayFrameRate frame rate}, stepping the schedule if so.
     *
     * @param showing whether a frame is on screen; if not, one is due at once
     * @return true to draw a frame now
     */
    private boolean due(boolean showing) {
        long now = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(1, Math.min(MAX_FRAME_RATE, config.overlayFrameRate()));
        if (showing && now - nextFrame < 0) {
            return false;
        }
        // Step the schedule rather than restarting it, so 30 fps on a 20 ms poll averages 30, not 25
        nextFrame = now - nextFrame > interval ? now + interval : nextFrame + interval;
        return true;
    }

    /**
     * Draws a frame for the game if one is due, at the framebuffer's size, and hands it to the {@link GameSurface}. With
     * no overlays, hands over one empty frame to clear the last, then nothing.
     *
     * @param session  the attached API
     * @param overlays the overlays, in drawing order
     */
    private void drawInGame(GameSession session, List<Overlay> overlays) {
        int width = surface.getFrameWidth();
        int height = surface.getFrameHeight();
        int canvasWidth = session.client().getCanvasWidth();
        int canvasHeight = session.client().getCanvasHeight();
        if (overlays.isEmpty() || canvasWidth < 1 || canvasHeight < 1) {
            if (gameShowing && gameImage != null) {
                clearRows(gameImage, gameTop, gameBottom);
                gameTop = 0;
                gameBottom = 0;
                gameShowing = !surface.publish(pixels(gameImage), gameImage.getWidth(), gameImage.getHeight(), 0, 0);
            }
            return;
        }
        if (!due(gameShowing)) {
            return;
        }
        if (gameImage == null || gameImage.getWidth() != width || gameImage.getHeight() != height) {
            gameImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            gameTop = 0;
            gameBottom = 0;
        } else {
            clearRows(gameImage, gameTop, gameBottom);
        }
        Graphics2D g = gameImage.createGraphics();
        try {
            g.scale(width / (double) canvasWidth, height / (double) canvasHeight);
            compositor.render(g, overlays, canvasWidth, canvasHeight);
        } finally {
            g.dispose();
        }
        int[] pixels = pixels(gameImage);
        int top = firstRow(pixels, width, height);
        int bottom = top == height ? top : lastRow(pixels, width, top, height) + 1;
        if (surface.publish(pixels, width, height, top, bottom)) {
            gameTop = top;
            gameBottom = bottom;
            gameShowing = bottom > top;
        } else {
            // The surface went away mid-frame: clear the whole image, since the rows drawn weren't recorded
            gameTop = 0;
            gameBottom = height;
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
        for (int row = 0; row < height; row++) {
            if (!blank(pixels, row * width, width)) {
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
        for (int row = height - 1; row > top; row--) {
            if (!blank(pixels, row * width, width)) {
                return row;
            }
        }
        return top;
    }

    private static boolean blank(int[] pixels, int start, int length) {
        for (int i = start, end = start + length; i < end; i++) {
            if (pixels[i] != 0) {
                return false;
            }
        }
        return true;
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
