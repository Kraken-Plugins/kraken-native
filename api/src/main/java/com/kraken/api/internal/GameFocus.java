package com.kraken.api.internal;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.GUITHREADINFO;
import com.sun.jna.ptr.IntByReference;

import java.util.function.BooleanSupplier;

/**
 * Whether a process has the keyboard focus, from Windows' own focus state: the focus window of the foreground
 * thread belongs to the process. The client keeps no focus state of its own (its window procedures only let go of
 * held keys when they lose the focus), so this is what {@code Client.isFocused()} reads. It holds whether the game's
 * window is a top-level window or a child of another process's window: a child shares its parent's input state, so
 * the foreground thread's focus window is the game's while the game has the keys. Internal: {@code GameSession}
 * hands one to the client it attaches.
 */
public final class GameFocus implements BooleanSupplier {

    private final long pid;

    /**
     * Creates the check for a process.
     *
     * @param pid the game's process id
     */
    public GameFocus(long pid) {
        this.pid = pid;
    }

    /**
     * Whether the process has the keyboard focus now.
     *
     * @return true if the foreground thread's focus window belongs to the process; false if it doesn't, there is no
     *         foreground window, or Windows can't say
     */
    @Override
    public boolean getAsBoolean() {
        GUITHREADINFO info = new GUITHREADINFO();
        if (!User32.INSTANCE.GetGUIThreadInfo(0, info)) {
            return false;
        }
        HWND focus = info.hwndFocus;
        if (focus == null) {
            return false;
        }
        IntByReference owner = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(focus, owner);
        return owner.getValue() == pid;
    }
}
