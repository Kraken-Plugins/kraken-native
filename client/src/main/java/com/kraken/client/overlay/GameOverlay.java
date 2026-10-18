package com.kraken.client.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.GameView;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import lombok.extern.java.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static com.sun.jna.platform.win32.WinUser.*;

/**
 * A transparent window laid exactly over the embedded game, for drawing overlays (labels above NPCs, tile
 * highlights, info panels). The game is another process's window inside a heavyweight canvas, so Swing can't paint
 * on it directly. Instead this window is owned by the Kraken frame, so it stays just above it and hides with it, and
 * every mouse click passes straight through it to the game.
 *
 * <p>It only shows while a registered {@link OverlayPainter} is active. {@link #refresh} is thread-safe.
 */
@Log(topic = "kraken")
@Singleton
public final class GameOverlay {
    private static final int WS_EX_NOACTIVATE = 0x08000000; // not in JNA's WinUser

    private final GameView view;
    private final List<OverlayPainter> painters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private JWindow window;

    @Inject
    GameOverlay(GameView view) {
        this.view = view;
    }

    public void addPainter(OverlayPainter painter) {
        painters.add(painter);
        refresh();
    }

    /** Shows or hides the overlay as needed, keeps it over the game, and repaints it. Call from any thread. */
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
        boolean minimized = frame instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0;
        boolean wanted = frame != null && view.isShowing() && !minimized
                && painters.stream().anyMatch(OverlayPainter::isActive);
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

        // Follow the game when the frame moves, resizes or minimizes, or the sidebar/console resize the game
        ComponentAdapter follow = new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                refresh();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }
        };
        frame.addComponentListener(follow);
        view.addComponentListener(follow);
        frame.addWindowStateListener(e -> refresh());
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

    /** Lets mouse input fall through to the game and keeps the overlay from ever taking focus. */
    private static void clickThrough(Window w) {
        HWND hwnd = new HWND(Native.getWindowPointer(w));
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
    }
}
