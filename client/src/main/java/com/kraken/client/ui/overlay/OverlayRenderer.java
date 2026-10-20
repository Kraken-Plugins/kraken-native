package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.KrakenApi;
import com.kraken.api.lua.Vector2;
import com.kraken.client.ApiConnection;
import com.kraken.client.config.KrakenConfig;
import lombok.extern.java.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Level;

/**
 * Draws the plugins' {@link Overlay}s over the game. The client creates it at launch.
 *
 * <ul>
 *   <li><b>On the client thread.</b> At the end of each API poll, if overlays are added and a frame is due (the
 *       {@linkplain KrakenConfig#overlayFrameRate frame rate}, 30 a second by default), the poll thread clears an
 *       image, draws every overlay into it ({@link OverlayCompositor}) and hands it to the EDT. Overlays' {@code render}
 *       thus runs where game events and {@code ClientThread} tasks do, as in RuneLite, and memory reads stay off the
 *       EDT.
 *   <li><b>Two images.</b> The EDT paints the last finished frame while the next one is drawn into the other, so it
 *       never shows a half-drawn frame. Each image is locked while it's drawn or painted, for the rare frame where the
 *       EDT is still painting an image when its turn to be drawn into comes round.
 *   <li><b>Canvas pixels.</b> The image is sized in device pixels, so text stays sharp on scaled displays, and the
 *       graphics handed to overlays is scaled so they draw in the client's canvas pixels, where {@code Perspective}
 *       results land.
 * </ul>
 *
 * <p>Nothing is read or drawn while no overlay is added or the game area isn't showing. The frames go through the
 * {@link GameOverlay} window, alongside the dev tools' overlays.
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
    private final KrakenConfig config;
    private final OverlayCompositor compositor;
    private volatile Frame front;
    private Frame back;
    private long nextFrame = System.nanoTime();
    private boolean failing;

    @Inject
    OverlayRenderer(OverlayManager manager, GameOverlay window, ApiConnection connection, KrakenConfig config) {
        this.manager = manager;
        this.window = window;
        this.config = config;
        Font font = UIManager.getFont("Label.font");
        this.compositor = new OverlayCompositor(font, font.deriveFont(Font.BOLD));
        window.addPainter(new OverlayPainter() {
            @Override
            public boolean isActive() {
                // Not just the connection's state: once the game exits, the poll loop stops and the last frame stays
                KrakenApi api = connection.api();
                return front != null && api != null && api.isAttached();
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
        connection.addFrameListener(this::frame);
    }

    /**
     * Draws a frame if one is due. Poll thread.
     *
     * @param api the attached API
     */
    private void frame(KrakenApi api) {
        try {
            draw(api);
            failing = false;
        } catch (RuntimeException | LinkageError e) {
            if (!failing) {
                failing = true;
                log.log(Level.WARNING, "Drawing the overlays failed", e);
            }
        }
    }

    private void draw(KrakenApi api) {
        List<Overlay> overlays = manager.getOverlays();
        GameOverlay.Surface surface = window.getSurface();
        if (overlays.isEmpty() || surface == null) {
            clear();
            return;
        }
        long now = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(1, Math.min(MAX_FRAME_RATE, config.overlayFrameRate()));
        if (front != null && now - nextFrame < 0) {
            return;
        }
        // Step the schedule rather than restarting it, so 30 fps on a 20 ms poll averages 30, not 25
        nextFrame = now - nextFrame > interval ? now + interval : nextFrame + interval;

        Vector2 canvas = api.lua().graphics().clientDimensions();
        if (canvas == null || canvas.x() < 1 || canvas.y() < 1) {
            clear();
            return;
        }
        int width = surface.deviceWidth();
        int height = surface.deviceHeight();
        Frame frame = back != null && back.fits(width, height) ? back : new Frame(width, height);
        synchronized (frame) {
            Graphics2D g = frame.image.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, width, height);
                g.setComposite(AlphaComposite.SrcOver);
                g.scale(width / canvas.x(), height / canvas.y());
                compositor.render(g, overlays, (int) canvas.x(), (int) canvas.y());
            } finally {
                g.dispose();
            }
        }
        back = front;
        front = frame;
        window.refresh();
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
