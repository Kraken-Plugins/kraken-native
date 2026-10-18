package com.kraken.client.devtools.view;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.prefs.Preferences;

/**
 * A popout window for one dev tool. It's owned by the Kraken frame, so it floats above the game without taking a
 * taskbar slot, and it reopens where it was last closed. Closing it (or pressing Esc) runs {@code onClose}; the
 * owner then calls {@link #close}.
 */
public final class ToolWindow extends JDialog {
    private static final int CASCADE = 28;

    private final Preferences prefs;
    private final String boundsKey;

    /**
     * @param prefs     where to remember the window's bounds, under {@code boundsKey}
     * @param cascade   how many tool windows are already open, to offset a first-time placement
     */
    public ToolWindow(Window owner, String title, JComponent content, Preferences prefs, String boundsKey, int cascade,
                      Runnable onClose) {
        super(owner, title, ModalityType.MODELESS);
        this.prefs = prefs;
        this.boundsKey = boundsKey;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose.run();
            }
        });
        getRootPane().registerKeyboardAction(e -> onClose.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setContentPane(content);
        setMinimumSize(new Dimension(300, 180));
        pack();
        Rectangle saved = savedBounds();
        if (saved != null) {
            setBounds(saved);
        } else {
            placeBeside(owner, cascade * CASCADE);
        }
    }

    /** Remembers where the window is, then disposes it. */
    public void close() {
        Rectangle b = getBounds();
        prefs.put(boundsKey, b.x + "," + b.y + "," + b.width + "," + b.height);
        dispose();
    }

    /** To the right of the owner if it fits on that screen, else just inside its right edge. */
    private void placeBeside(Window owner, int offset) {
        GraphicsConfiguration gc = owner.getGraphicsConfiguration();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle screen = gc.getBounds();
        int left = screen.x + insets.left;
        int right = screen.x + screen.width - insets.right;
        int top = screen.y + insets.top;
        int bottom = screen.y + screen.height - insets.bottom;

        int x = owner.getX() + owner.getWidth() + 8 + offset;
        if (x + getWidth() > right) {
            x = owner.getX() + owner.getWidth() - getWidth() - 48 - offset;
        }
        int y = owner.getY() + 48 + offset;
        setLocation(Math.max(left, Math.min(x, right - getWidth())), Math.max(top, Math.min(y, bottom - getHeight())));
    }

    /** The bounds this window was last closed at, if they're still on a connected screen. */
    private Rectangle savedBounds() {
        String[] parts = prefs.get(boundsKey, "").split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            Rectangle r = new Rectangle(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            Point titleBar = new Point(r.x + Math.min(40, r.width / 2), r.y + 10);
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                if (screen.getDefaultConfiguration().getBounds().contains(titleBar)) {
                    return r;
                }
            }
        } catch (NumberFormatException ignored) {
            // corrupt entry: fall back to the default placement
        }
        return null;
    }
}
