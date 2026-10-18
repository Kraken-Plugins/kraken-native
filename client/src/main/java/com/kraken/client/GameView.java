package com.kraken.client;

import com.google.inject.Singleton;
import com.kraken.client.ui.Theme;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.atomic.AtomicReference;

import static com.sun.jna.platform.win32.WinUser.*;

/**
 * Heavyweight canvas the osclient window is re-parented into (it needs its own HWND).
 * Shows a splash with a status message until the game window covers it.
 */
@Singleton
public final class GameView extends Canvas {
    private final Image logo = Theme.logoImage(96);
    private volatile String message = "Launching Old School RuneScape…";
    private volatile HWND game;

    GameView() {
        setBackground(Theme.DARKEST);
        setPreferredSize(new Dimension(765, 503));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fit();
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                focusGame();
            }
        });
    }

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

    void setMessage(String message) {
        this.message = message;
        repaint();
    }

    /** The first visible top-level window owned by {@code pid}, or null if it hasn't been created yet. */
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
        User32.INSTANCE.SetParent(hwnd, canvasHwnd());
        game = hwnd;
        fit();
        SwingUtilities.invokeLater(this::focusGame);
    }

    /** Stretches the game window over the canvas, in physical pixels. Fires "gameSize" when it changes. */
    void fit() {
        HWND hwnd = game;
        if (hwnd == null) {
            return;
        }
        RECT want = new RECT();
        RECT have = new RECT();
        User32.INSTANCE.GetClientRect(canvasHwnd(), want);
        User32.INSTANCE.GetWindowRect(hwnd, have);
        if (have.right - have.left != want.right || have.bottom - have.top != want.bottom) {
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, want.right, want.bottom, SWP_NOZORDER | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
            firePropertyChange("gameSize", null, new Dimension(want.right, want.bottom));
        }
    }

    /** Hands keyboard focus to the game. Its window belongs to another thread, so join that thread's input state first. */
    private void focusGame() {
        HWND hwnd = game;
        if (hwnd == null) {
            return;
        }
        DWORD gameThread = new DWORD(User32.INSTANCE.GetWindowThreadProcessId(hwnd, null));
        User32.INSTANCE.AttachThreadInput(new DWORD(Kernel32.INSTANCE.GetCurrentThreadId()), gameThread, true);
        User32.INSTANCE.SetFocus(hwnd);
    }

    private HWND canvasHwnd() {
        return new HWND(Native.getComponentPointer(this));
    }
}
