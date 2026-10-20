package com.kraken.client;

import com.google.inject.Singleton;
import com.kraken.client.ui.Theme;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser.GUITHREADINFO;
import com.sun.jna.ptr.IntByReference;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import static com.sun.jna.platform.win32.WinUser.*;

/**
 * Where the osclient window lives. The game is re-parented into a heavyweight canvas (it needs its own HWND), which
 * shows a splash with a status message until the game window covers it.
 *
 * <p>The view also stands in for the game in Swing's focus system, so the keyboard follows the user between the game
 * and Kraken's own controls. The game takes Win32 focus itself when it's clicked (it calls {@code SetFocus} on every
 * mouse-button press), but AWT's frame swallows the {@code WM_KILLFOCUS} that comes with it, so Swing goes on thinking
 * the control it focused last has the keyboard. AWT moves Swing's focus between lightweight components of one frame
 * without touching Win32 focus, so clicking that control again, or another one, would leave the keys going to the
 * game. So once the game has the keyboard the view takes Swing's focus, and when Swing's focus moves from the view to a
 * Kraken control, the frame takes Win32 focus back.
 */
@Singleton
public final class GameView extends JPanel {

    /**
     * How often to check whether the game has taken the keyboard, in milliseconds.
     */
    private static final int FOCUS_CHECK_MS = 100;

    private final Image logo = Theme.logoImage(96);
    private final Canvas host = new Host();
    private final Timer focusCheck = new Timer(FOCUS_CHECK_MS, e -> followGame());
    private volatile String message = "Launching Old School RuneScape…";
    private volatile HWND game;

    GameView() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(765, 503));
        setFocusable(true);
        host.setBackground(Theme.DARKEST);
        host.setFocusable(false);
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fit();
            }
        });
        add(host, BorderLayout.CENTER);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                focusGame();
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("focusOwner", e -> focusOwnerChanged((Component) e.getNewValue()));
    }

    void setMessage(String message) {
        this.message = message;
        host.repaint();
    }

    /**
     * Finds the game's window.
     *
     * @param pid the game's process id
     * @return the first visible top-level window owned by {@code pid}, or null if it hasn't been created yet
     */
    static HWND find(long pid) {
        AtomicReference<HWND> found = new AtomicReference<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
            if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                found.set(hwnd);
                return false;
            }
            return true;
        }, null);
        return found.get();
    }

    void embed(HWND hwnd) {
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
        style &= ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
        User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, style | WS_CHILD);
        User32.INSTANCE.SetParent(hwnd, hostHwnd());
        game = hwnd;
        fit();
        SwingUtilities.invokeLater(() -> {
            focusGame();
            focusCheck.start();
        });
    }

    /**
     * Stretches the game window over the canvas, in physical pixels. Fires "gameSize" when it changes.
     */
    void fit() {
        HWND hwnd = game;
        if (hwnd == null) {
            return;
        }
        RECT want = new RECT();
        RECT have = new RECT();
        User32.INSTANCE.GetClientRect(hostHwnd(), want);
        User32.INSTANCE.GetWindowRect(hwnd, have);
        if (have.right - have.left != want.right || have.bottom - have.top != want.bottom) {
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, want.right, want.bottom, SWP_NOZORDER | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
            firePropertyChange("gameSize", null, new Dimension(want.right, want.bottom));
        }
    }

    /**
     * Hands Win32 focus to the game, unless it has it already: focusing its window again would move the focus off the
     * child window the game keeps it on, and the game lets go of every held key when that loses focus.
     */
    private void focusGame() {
        HWND hwnd = game;
        if (hwnd != null && !gameHasFocus()) {
            setFocus(hwnd);
        }
    }

    /**
     * Takes Swing's focus from a Kraken control once the game has the keyboard, so the control stops looking focused
     * and clicking it again counts as a focus change (see {@link #focusOwnerChanged}). Both are lightweight components
     * of the frame, so AWT moves Swing's focus without touching Win32 focus, and the game keeps the keyboard.
     */
    private void followGame() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null && owner != this && gameHasKeyboard()) {
            requestFocusInWindow();
        }
    }

    /**
     * Gives the frame Win32 focus back when Swing's focus moves to a Kraken control (a click on a text field, say)
     * while the game has the keyboard. AWT doesn't, as the game's focus never reached Swing.
     *
     * @param owner the new focus owner, or null if Swing's focus was cleared
     */
    private void focusOwnerChanged(Component owner) {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (owner != null && owner != this && SwingUtilities.getWindowAncestor(owner) == window && gameHasKeyboard()) {
            setFocus(new HWND(Native.getWindowPointer(window)));
        }
    }

    /**
     * Whether keys typed now go to the game: Kraken's frame is the focused window and the game has Win32 focus.
     *
     * @return true if the game has the keyboard
     */
    private boolean gameHasKeyboard() {
        Window window = SwingUtilities.getWindowAncestor(this);
        return window != null && window.isFocused() && gameHasFocus();
    }

    /**
     * Whether Win32 focus is on the game's window or one of its children (the game passes its focus on to a child).
     *
     * @return true if the game has Win32 focus
     */
    private boolean gameHasFocus() {
        HWND hwnd = game;
        if (hwnd == null) {
            return false;
        }
        GUITHREADINFO info = new GUITHREADINFO();
        if (!User32.INSTANCE.GetGUIThreadInfo(User32.INSTANCE.GetWindowThreadProcessId(hwnd, null), info)) {
            return false;
        }
        for (HWND w = info.hwndFocus; w != null; w = User32.INSTANCE.GetParent(w)) {
            if (w.equals(hwnd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves Win32 focus to {@code target}, the game or the frame. {@code SetFocus} only reaches windows of threads
     * that share this thread's input state, so this thread joins the game's thread first, which shares it with the
     * frame's thread because the game window is the frame's child. It stays joined after.
     *
     * @param target the window to focus
     */
    private void setFocus(HWND target) {
        DWORD self = new DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        DWORD gameThread = new DWORD(User32.INSTANCE.GetWindowThreadProcessId(game, null));
        User32.INSTANCE.AttachThreadInput(self, gameThread, true);
        User32.INSTANCE.SetFocus(target);
    }

    private HWND hostHwnd() {
        return new HWND(Native.getComponentPointer(host));
    }

    /**
     * The canvas the game window is re-parented into. It shows the logo and the status message until the game covers
     * it.
     */
    private final class Host extends Canvas {
        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g2.drawImage(logo, cx - 48, cy - 72, 96, 96, null);
            g2.setFont(UIManager.getFont("large.font"));
            g2.setColor(Theme.LIGHT);
            g2.drawString(message, cx - g2.getFontMetrics().stringWidth(message) / 2, cy + 52);
        }
    }
}
