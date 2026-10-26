package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.GameView;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import lombok.Getter;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;

import static com.sun.jna.platform.win32.WinUser.GWL_EXSTYLE;
import static com.sun.jna.platform.win32.WinUser.VK_MENU;

/**
 * Lets the user move and resize overlays by holding Alt. While Alt is held and a Kraken window is active, every overlay
 * that {@linkplain #isMovable can be moved} is outlined ({@link OverlayCompositor#setEditing}), and a clear window over
 * the game takes the mouse where those overlays are; everywhere else the game still gets it. Over an overlay:
 *
 * <ul>
 *   <li>dragging moves it, off its anchor to a {@linkplain Overlay#getPreferredLocation() preferred location};
 *   <li>dragging one of its edges or corners resizes it, if it is {@linkplain Overlay#isResizable() resizable};
 *   <li>a right-click puts it back at its anchor and natural size.
 * </ul>
 *
 * <p>Each change is saved ({@link OverlayManager#saveOverlay}) when the button is released, and a drag carries on if
 * Alt is let go before then.
 *
 * <p>The game has the keyboard, so Alt is read from the key's state on a timer. The overlays' bounds come from the
 * renderer after each frame ({@link #frameDrawn}), in canvas pixels; the window works in the game area's UI pixels and
 * scales between the two. The renderer creates the editor.
 */
@Singleton
public final class OverlayEditor {

    /**
     * How often to check Alt, in milliseconds.
     */
    private static final int POLL_MS = 30;

    /**
     * Nearly clear: Windows lets clicks through the fully clear pixels of a translucent window, so the areas that
     * catch the mouse keep a little alpha.
     */
    private static final Color CATCH = new Color(0, 0, 0, 1);

    /**
     * {@code WS_EX_NOACTIVATE}, which JNA's {@code WinUser} doesn't have: a click on the window leaves Kraken's frame
     * active, so the game keeps the keyboard.
     */
    private static final int WS_EX_NOACTIVATE = 0x08000000;

    private final GameView view;
    private final OverlayManager manager;
    private final Timer poll = new Timer(POLL_MS, e -> poll());

    /**
     * The movable overlays in the last frame drawn.
     */
    private volatile Layout layout = Layout.EMPTY;

    /**
     * Whether the user is moving overlays: Alt is held, or a drag is under way.
     */
    @Getter
    private volatile boolean editing;

    /**
     * The overlay under the pointer or being dragged, or null.
     */
    @Getter
    private volatile Overlay target;

    /**
     * Whether {@link #getTarget()} is being resized rather than moved.
     */
    @Getter
    private volatile boolean resizing;

    private JWindow window;
    private JComponent content;
    private OverlayDrag drag;

    /**
     * The areas the window catches the mouse in, in UI pixels; null to paint them again.
     */
    private List<Rectangle> caught;

    @Inject
    OverlayEditor(GameView view, OverlayManager manager) {
        this.view = view;
        this.manager = manager;
        poll.start();
    }

    /**
     * Whether the user can drag an overlay: it is {@linkplain Overlay#isMovable() movable} and not
     * {@link OverlayPosition#DYNAMIC}.
     *
     * @param overlay the overlay
     * @return true if it can be dragged
     */
    static boolean isMovable(Overlay overlay) {
        return overlay.isMovable() && OverlayManager.effectivePosition(overlay) != OverlayPosition.DYNAMIC;
    }

    /**
     * Notes where the movable overlays were drawn. The renderer calls it on the poll thread after each frame.
     *
     * @param overlays     the overlays drawn
     * @param canvasWidth  the canvas's width
     * @param canvasHeight the canvas's height
     */
    void frameDrawn(List<Overlay> overlays, int canvasWidth, int canvasHeight) {
        List<Placed> placed = new ArrayList<>();
        for (Overlay overlay : overlays) {
            if (isMovable(overlay) && !overlay.getBounds().isEmpty()) {
                placed.add(new Placed(overlay, new Rectangle(overlay.getBounds())));
            }
        }
        // In the order they were drawn, layer by layer, so the last one under the pointer is the one on top
        placed.sort(Comparator.comparing(p -> p.overlay().getLayer()));
        layout = placed.isEmpty() ? Layout.EMPTY : new Layout(List.copyOf(placed), canvasWidth, canvasHeight);
    }

    /**
     * Notes that no overlay is on screen. The renderer calls it on the poll thread.
     */
    void nothingDrawn() {
        layout = Layout.EMPTY;
    }

    /**
     * Starts or stops editing as Alt goes down or up, and keeps the window over the game. EDT.
     */
    private void poll() {
        boolean wanted = drag != null
                || (!layout.placed().isEmpty() && altDown() && krakenActive() && view.isShowing());
        if (wanted != editing) {
            if (wanted) {
                start();
            } else {
                stop();
            }
        }
        if (editing && window != null) {
            place();
            catchOverlays();
        }
    }

    /**
     * Lays the window over the game. EDT.
     */
    private void start() {
        Window frame = SwingUtilities.getWindowAncestor(view);
        if (frame == null) {
            return;
        }
        if (window == null || window.getOwner() != frame) {
            if (window != null) {
                window.dispose();
            }
            window = create(frame);
        }
        editing = true;
        caught = null;
        place();
        catchOverlays();
        if (!window.isVisible()) {
            noActivate(window);
            window.setVisible(true);
        }
        // Outline the overlay under the pointer at once, rather than once the pointer moves
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer != null) {
            Point at = pointer.getLocation();
            SwingUtilities.convertPointFromScreen(at, view);
            hover(view.contains(at) ? at : null);
        }
    }

    /**
     * Takes the window off the game. EDT.
     */
    private void stop() {
        editing = false;
        target = null;
        resizing = false;
        if (window != null) {
            window.setVisible(false);
        }
    }

    private JWindow create(Window frame) {
        JWindow w = new JWindow(frame);
        w.setFocusableWindowState(false);
        w.setAutoRequestFocus(false);
        w.setBackground(new Color(0, 0, 0, 0)); // per-pixel transparent
        w.getRootPane().setOpaque(false);
        content = new JComponent() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setComposite(AlphaComposite.Src);
                    g.setColor(CATCH);
                    if (drag != null) {
                        // All of it while dragging, so the pointer can't slip off onto the game
                        g.fillRect(0, 0, getWidth(), getHeight());
                    } else if (caught != null) {
                        caught.forEach(g::fill);
                    }
                } finally {
                    g.dispose();
                }
            }
        };
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (drag == null) {
                    hover(null);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                pressed(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (drag != null) {
                    drag.dragTo(toCanvas(e.getPoint(), drag.canvas()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (drag != null && SwingUtilities.isLeftMouseButton(e)) {
                    manager.saveOverlay(drag.overlay());
                    drag = null;
                    resizing = false;
                    caught = null;
                    catchOverlays();
                    hover(e.getPoint());
                }
            }
        };
        content.addMouseListener(mouse);
        content.addMouseMotionListener(mouse);
        w.setContentPane(content);
        w.pack(); // create the native window now, so it can be kept from activating before it first shows
        return w;
    }

    /**
     * Starts a drag, or resets an overlay on a right-click. EDT.
     *
     * @param e the press
     */
    private void pressed(MouseEvent e) {
        if (drag != null) {
            return;
        }
        Layout current = layout;
        Placed hit = hit(current, e.getPoint());
        if (hit == null) {
            return;
        }
        if (SwingUtilities.isRightMouseButton(e)) {
            manager.resetOverlay(hit.overlay());
        } else if (SwingUtilities.isLeftMouseButton(e)) {
            Dimension canvas = new Dimension(current.canvasWidth(), current.canvasHeight());
            Point at = toCanvas(e.getPoint(), canvas);
            int edges = hit.overlay().isResizable() ? OverlayDrag.edgesAt(hit.bounds(), at) : 0;
            drag = new OverlayDrag(hit.overlay(), hit.bounds(), at, edges, canvas);
            target = hit.overlay();
            resizing = drag.resizing();
            content.repaint();
        }
    }

    /**
     * Notes the overlay under the pointer, and shows what a press there would do. EDT.
     *
     * @param point where the pointer is, in UI pixels; null if it left the window
     */
    private void hover(Point point) {
        if (drag != null) {
            return;
        }
        Layout current = layout;
        Placed hit = point == null ? null : hit(current, point);
        target = hit == null ? null : hit.overlay();
        int cursor = Cursor.DEFAULT_CURSOR;
        if (hit != null) {
            Point at = toCanvas(point, new Dimension(current.canvasWidth(), current.canvasHeight()));
            cursor = OverlayDrag.cursor(hit.overlay().isResizable() ? OverlayDrag.edgesAt(hit.bounds(), at) : 0);
        }
        content.setCursor(Cursor.getPredefinedCursor(cursor));
    }

    /**
     * The overlay at a point: the one drawn last, if several overlap.
     *
     * @param current the overlays' bounds
     * @param point   the point, in UI pixels
     * @return the overlay and its bounds, or null if there is none there
     */
    private Placed hit(Layout current, Point point) {
        Point at = toCanvas(point, new Dimension(current.canvasWidth(), current.canvasHeight()));
        for (int i = current.placed().size() - 1; i >= 0; i--) {
            Placed placed = current.placed().get(i);
            Rectangle bounds = placed.bounds();
            // A pixel's grace on every side, for the rounding between UI and canvas pixels
            Rectangle grown = new Rectangle(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
            if (grown.contains(at)) {
                return placed;
            }
        }
        return null;
    }

    /**
     * Paints the areas the window catches the mouse in again if the overlays moved. EDT.
     */
    private void catchOverlays() {
        if (drag != null) {
            return;
        }
        Layout current = layout;
        List<Rectangle> areas = new ArrayList<>(current.placed().size());
        for (Placed placed : current.placed()) {
            areas.add(toUi(placed.bounds(), current));
        }
        if (!areas.equals(caught)) {
            caught = areas;
            content.repaint();
        }
    }

    /**
     * Keeps the window exactly over the game area. EDT.
     */
    private void place() {
        if (!view.isShowing()) {
            return;
        }
        Rectangle bounds = new Rectangle(view.getLocationOnScreen(), view.getSize());
        if (!bounds.equals(window.getBounds())) {
            window.setBounds(bounds);
        }
    }

    /**
     * A point in the game area's UI pixels in canvas pixels.
     *
     * @param point  the point
     * @param canvas the canvas's size
     * @return the point on the canvas
     */
    private Point toCanvas(Point point, Dimension canvas) {
        double scaleX = canvas.width / (double) Math.max(1, view.getWidth());
        double scaleY = canvas.height / (double) Math.max(1, view.getHeight());
        return new Point((int) Math.floor(point.x * scaleX), (int) Math.floor(point.y * scaleY));
    }

    /**
     * An area in canvas pixels in the game area's UI pixels, rounded out to cover it.
     *
     * @param area    the area on the canvas
     * @param current the layout, for the canvas's size
     * @return the area in UI pixels
     */
    private Rectangle toUi(Rectangle area, Layout current) {
        double scaleX = view.getWidth() / (double) Math.max(1, current.canvasWidth());
        double scaleY = view.getHeight() / (double) Math.max(1, current.canvasHeight());
        int left = (int) Math.floor(area.x * scaleX);
        int top = (int) Math.floor(area.y * scaleY);
        int right = (int) Math.ceil((area.x + area.width) * scaleX);
        int bottom = (int) Math.ceil((area.y + area.height) * scaleY);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    /**
     * Whether either Alt key is down, wherever the keyboard is.
     *
     * @return true while Alt is held
     */
    private static boolean altDown() {
        return (User32.INSTANCE.GetAsyncKeyState(VK_MENU) & 0x8000) != 0;
    }

    /**
     * Whether one of Kraken's windows is active, the frame (with the game in it) or a dev tool, so Alt is held for
     * Kraken and not for another program.
     *
     * @return true if a Kraken window is active
     */
    private static boolean krakenActive() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
    }

    /**
     * Keeps a click on the window from activating it, so Kraken's frame stays active and the game keeps the keyboard.
     *
     * @param w the window
     */
    private static void noActivate(Window w) {
        HWND hwnd = new HWND(Native.getWindowPointer(w));
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style | WS_EX_NOACTIVATE);
    }

    /**
     * Where a movable overlay was drawn.
     *
     * @param overlay the overlay
     * @param bounds  its bounds, in canvas pixels
     */
    private record Placed(Overlay overlay, Rectangle bounds) {
    }

    /**
     * The movable overlays in one frame.
     *
     * @param placed       each one and its bounds, in drawing order
     * @param canvasWidth  the canvas's width
     * @param canvasHeight the canvas's height
     */
    private record Layout(List<Placed> placed, int canvasWidth, int canvasHeight) {
        static final Layout EMPTY = new Layout(List.of(), 0, 0);
    }
}
