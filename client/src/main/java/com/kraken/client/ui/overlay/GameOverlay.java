package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.GameView;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import lombok.extern.java.Log;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.swing.*;

import static com.sun.jna.platform.win32.WinUser.*;

/**
 * A transparent window laid exactly over the embedded game, for drawing overlays (labels above NPCs, tile
 * highlights, info panels). The game is another process's window inside a heavyweight canvas, so Swing can't paint
 * on it directly. Instead this window is owned by the Kraken frame, so it stays just above it and hides with it, and
 * every mouse click passes straight through it to the game.
 *
 * <p>It only shows while a registered {@link OverlayPainter} is active. It also keeps the game area's size and scale
 * ({@link #getSurface()}), which the plugin overlay renderer sizes its frames by. {@link #refresh} is thread-safe.
 */
@Log(topic = "kraken")
@Singleton
public final class GameOverlay {

    /**
     * {@code WS_EX_NOACTIVATE}, which JNA's {@code WinUser} doesn't have.
     */
    private static final int WS_EX_NOACTIVATE = 0x08000000;

    private final GameView view;
    private final List<OverlayPainter> painters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile Surface surface;
    private Window followed;
    private JWindow window;

    @Inject
    GameOverlay(GameView view) {
        this.view = view;
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                refresh();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                refresh();
            }
        });
        refresh();
    }

    /**
     * Adds something to draw. Painters draw in the order they were added.
     *
     * @param painter the painter
     */
    public void addPainter(OverlayPainter painter) {
        painters.add(painter);
        refresh();
    }

    /**
     * The game area as it was at the last refresh: its size in UI pixels and the screen's scale.
     *
     * @return the surface, or null while the game area isn't showing (the frame is minimized, say)
     */
    public Surface getSurface() {
        return surface;
    }

    /**
     * Shows or hides the overlay as needed, keeps it over the game, and repaints it. Call from any thread.
     */
    public void refresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                refreshQueued.set(false);
                update();
            });
        }
    }

    private void update() {
        Window frame = SwingUtilities.getWindowAncestor(view);
        if (frame != null && frame != followed) {
            follow(frame);
        }
        boolean minimized = frame instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0;
        boolean showing = frame != null && view.isShowing() && !minimized;
        surface = showing ? measure() : null;
        boolean wanted = showing && painters.stream().anyMatch(OverlayPainter::isActive);
        if (!wanted) {
            if (window != null && window.isVisible()) {
                window.setVisible(false);
            }
            return;
        }
        if (window == null) {
            window = create(frame);
        }
        Rectangle bounds = new Rectangle(view.getLocationOnScreen(), view.getSize());
        if (!bounds.equals(window.getBounds())) {
            window.setBounds(bounds);
        }
        if (!window.isVisible()) {
            clickThrough(window);
            window.setVisible(true);
        }
        window.repaint();
    }

    /**
     * The game area's size and its screen's scale.
     *
     * @return the surface, or null if the game area has no size yet
     */
    private Surface measure() {
        Dimension size = view.getSize();
        if (size.width <= 0 || size.height <= 0) {
            return null;
        }
        GraphicsConfiguration config = view.getGraphicsConfiguration();
        AffineTransform scale = config == null ? new AffineTransform() : config.getDefaultTransform();
        return new Surface(size.width, size.height, scale.getScaleX(), scale.getScaleY());
    }

    /**
     * Follows the frame as it moves, resizes or minimizes.
     *
     * @param frame the window the game is embedded in
     */
    private void follow(Window frame) {
        followed = frame;
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                refresh();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }
        });
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                refresh();
            }
        });
        frame.addWindowStateListener(e -> refresh());
    }

    private JWindow create(Window frame) {
        JWindow w = new JWindow(frame);
        w.setFocusableWindowState(false);
        w.setAutoRequestFocus(false);
        w.setBackground(new Color(0, 0, 0, 0)); // per-pixel transparent
        w.getRootPane().setOpaque(false);
        w.setContentPane(new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                paintPainters((Graphics2D) g, getWidth(), getHeight());
            }
        });
        w.pack(); // create the native window now, so it can be made click-through before it first shows
        return w;
    }

    private void paintPainters(Graphics2D g, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Grayscale text AA: subpixel AA needs an opaque background
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        OverlayContext context = new OverlayContext(g, width, height);
        for (OverlayPainter painter : painters) {
            if (painter.isActive()) {
                try {
                    painter.paint(context);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Overlay painter failed", e);
                }
            }
        }
    }

    /**
     * Lets mouse input fall through to the game and keeps the overlay from ever taking focus.
     *
     * @param w the overlay window
     */
    private static void clickThrough(Window w) {
        HWND hwnd = new HWND(Native.getWindowPointer(w));
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
    }

    /**
     * The game area an overlay covers.
     *
     * @param width  its width in UI pixels
     * @param height its height in UI pixels
     * @param scaleX device pixels per UI pixel across (above 1 on a scaled display)
     * @param scaleY device pixels per UI pixel down
     */
    public record Surface(int width, int height, double scaleX, double scaleY) {

        /**
         * The width in device pixels, what a sharp image of the area needs.
         *
         * @return the width, rounded up
         */
        public int deviceWidth() {
            return (int) Math.ceil(width * scaleX);
        }

        /**
         * The height in device pixels.
         *
         * @return the height, rounded up
         */
        public int deviceHeight() {
            return (int) Math.ceil(height * scaleY);
        }
    }
}
