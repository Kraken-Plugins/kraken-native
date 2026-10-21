package com.kraken.client.ui.devtools.view;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.*;

/**
 * Lets a tool have the user point at something in the game. While it is on, a clear window lies over the game area
 * and takes the mouse: the game gets no clicks or movement, and the handler hears where the pointer is, each click,
 * each turn of the wheel and Esc. The window follows the game area as the frame moves or resizes, and paints whatever
 * the handler draws, in the game area's UI pixels.
 */
public final class GamePicker {

    /**
     * Nearly clear: Windows lets clicks through the fully clear pixels of a translucent window, so every pixel keeps
     * a little alpha to catch them.
     */
    private static final Color CATCH = new Color(0, 0, 0, 1);

    /**
     * What the picker reports to. Every call is on the EDT.
     */
    public interface Handler {
        /**
         * The pointer moved.
         *
         * @param at where it is, in the game area's UI pixels; null once it leaves the game area
         */
        void moved(Point at);

        /**
         * A mouse button went down.
         *
         * @param at    where, in the game area's UI pixels
         * @param event the press, for its button and modifiers
         */
        void pressed(Point at, MouseEvent event);

        /**
         * The wheel turned.
         *
         * @param steps how far: negative away from the user, positive towards
         */
        void scrolled(int steps);

        /**
         * The user pressed Esc.
         */
        void cancelled();

        /**
         * Draws over the game.
         *
         * @param g      the graphics, in the game area's UI pixels
         * @param width  the game area's width
         * @param height the game area's height
         */
        void paint(Graphics2D g, int width, int height);
    }

    private final Component area;
    private final Handler handler;
    private final ComponentListener follow = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            place();
        }

        @Override
        public void componentResized(ComponentEvent e) {
            place();
        }
    };
    private Window frame;
    private JWindow window;

    /**
     * A picker over a game area, off until {@link #start()}.
     *
     * @param area    the component the game is embedded in
     * @param handler what hears the pointer and draws over the game
     */
    public GamePicker(Component area, Handler handler) {
        this.area = area;
        this.handler = handler;
    }

    /**
     * Whether the picker is on.
     *
     * @return true while it takes the mouse
     */
    public boolean isActive() {
        return window != null;
    }

    /**
     * Lays the picker over the game and takes the mouse and keyboard. Call on the EDT.
     *
     * @return true if it is on; false if the game area isn't showing
     */
    public boolean start() {
        if (window != null) {
            return true;
        }
        frame = SwingUtilities.getWindowAncestor(area);
        if (frame == null || !area.isShowing()) {
            return false;
        }
        JComponent content = new JComponent() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setComposite(AlphaComposite.Src);
                    g.setColor(CATCH);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    handler.paint(g, getWidth(), getHeight());
                } finally {
                    g.dispose();
                }
            }
        };
        content.setFocusable(true);
        content.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                handler.moved(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                handler.moved(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                handler.moved(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                handler.pressed(e.getPoint(), e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                handler.scrolled(e.getWheelRotation());
            }
        };
        content.addMouseListener(mouse);
        content.addMouseMotionListener(mouse);
        content.addMouseWheelListener(mouse);
        content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        content.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.cancelled();
            }
        });

        window = new JWindow(frame);
        window.setBackground(CATCH);
        window.setContentPane(content);
        area.addComponentListener(follow);
        frame.addComponentListener(follow);
        place();
        window.setVisible(true);
        window.toFront();
        content.requestFocusInWindow();
        return true;
    }

    /**
     * Takes the picker off the game, giving the game its mouse back. Call on the EDT.
     */
    public void stop() {
        if (window == null) {
            return;
        }
        area.removeComponentListener(follow);
        frame.removeComponentListener(follow);
        window.dispose();
        window = null;
        frame = null;
    }

    /**
     * Paints the picker again, after what the handler draws changed.
     */
    public void repaint() {
        if (window != null) {
            window.repaint();
        }
    }

    /**
     * Lays the window exactly over the game area.
     */
    private void place() {
        if (window == null || !area.isShowing()) {
            return;
        }
        Rectangle bounds = new Rectangle(area.getLocationOnScreen(), area.getSize());
        if (!bounds.equals(window.getBounds())) {
            window.setBounds(bounds);
        }
    }
}
