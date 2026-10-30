package com.kraken.client.input;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.Client;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.config.Keybind;
import com.sun.jna.platform.win32.User32;
import lombok.extern.java.Log;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;

/**
 * Tells plugins which keys are held while the game has the keyboard, and remaps the keys the game gets. The game
 * reads the keyboard itself, so Kraken gets none of its key events: the manager reads the keys' state from Windows
 * instead, and only counts keys while {@link Client#isFocused()}.
 *
 * <p>{@link HotkeyListener}s are polled on the client thread at each pass, about every 20 ms, while any is
 * registered, so a press shorter than that can be missed. {@link #isKeyPressed} and {@link #isPressed} read the keys
 * when called, from any thread. Key codes are {@link KeyEvent}'s.
 *
 * <p>Remapping goes through the client's native hook, which rewrites the game window's key messages before the game
 * sees them: {@link #setRemapTable} gives it a table of {@link KeyRemapMode}s and {@link #setRemapMode} picks the one
 * in force; a rule can move to another mode by itself as its key goes down, so the hook needs no round trip for
 * Enter starting typing. The table is remembered and sent whenever the hook (re)connects
 * ({@link #isRemappingAvailable()}). {@link KeyReport}s of what the hook did come back while a listener asks.
 */
@Log(topic = "kraken")
@Singleton
public class KeyManager {

    private static final int[] MODIFIERS = {InputEvent.CTRL_DOWN_MASK, InputEvent.ALT_DOWN_MASK,
            InputEvent.SHIFT_DOWN_MASK, InputEvent.META_DOWN_MASK};

    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;

    private final BooleanSupplier focused;
    private final ClientThread clientThread;
    private final IntPredicate windowsKeyDown;
    private final List<HotkeyListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KeyReport>> reportListeners = new CopyOnWriteArrayList<>();

    /**
     * Whether the poll task is queued. Client thread only.
     */
    private boolean polling;

    /**
     * The remapping as last set, sent to the hook when it connects. Guarded by {@code this}.
     */
    private List<KeyRemapMode> table = List.of();
    private int mode;
    private KeyHook hook;
    private boolean sendFailed;

    /**
     * A manager reading the keys from Windows.
     *
     * @param client       the game, for whether it has the keyboard
     * @param clientThread where listeners are polled
     */
    @Inject
    KeyManager(Client client, ClientThread clientThread) {
        this(client::isFocused, clientThread, vk -> (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0);
    }

    /**
     * A manager over a given key state, for tests.
     *
     * @param focused        whether the game has the keyboard
     * @param clientThread   where listeners are polled, or null to poll only through {@link #poll}
     * @param windowsKeyDown whether a key is held, by Windows virtual-key code
     */
    KeyManager(BooleanSupplier focused, ClientThread clientThread, IntPredicate windowsKeyDown) {
        this.focused = focused;
        this.clientThread = clientThread;
        this.windowsKeyDown = windowsKeyDown;
    }

    /**
     * Starts telling a listener when its keybind goes down and up.
     *
     * @param listener the listener
     */
    public void registerHotkeyListener(HotkeyListener listener) {
        listeners.add(listener);
        if (clientThread != null) {
            clientThread.invoke(this::startPolling);
        }
    }

    /**
     * Stops telling a listener about its keybind. It isn't told the keybind went up.
     *
     * @param listener the listener
     */
    public void unregisterHotkeyListener(HotkeyListener listener) {
        listeners.remove(listener);
    }

    /**
     * Whether a key is held while the game has the keyboard.
     *
     * @param keyCode a {@link KeyEvent} key code, such as {@link KeyEvent#VK_SHIFT}
     * @return true if it is held now; false while the game doesn't have the keyboard
     */
    public boolean isKeyPressed(int keyCode) {
        return focused.getAsBoolean() && down(keyCode);
    }

    /**
     * Whether all of a keybind's keys are held while the game has the keyboard. Other keys held as well don't matter.
     *
     * @param keybind the keybind
     * @return true if it is held now; false for {@link Keybind#NOT_SET}
     */
    public boolean isPressed(Keybind keybind) {
        return focused.getAsBoolean() && held(keybind);
    }

    /**
     * Whether keys can be remapped now: the native hook is connected with the game window's procedure hooked.
     *
     * @return true while a table reaches the hook
     */
    public synchronized boolean isRemappingAvailable() {
        return hook != null;
    }

    /**
     * Sets the remapping table the hook applies, replacing the last. It is kept and sent again whenever the hook
     * connects. The mode in force stays as set; set it again if the table's modes changed meaning.
     *
     * @param modes the modes, at most {@link KeyHook#MAX_MODES}; empty passes every key through
     * @throws IllegalArgumentException if there are too many modes
     */
    public synchronized void setRemapTable(List<KeyRemapMode> modes) {
        if (modes.size() > KeyHook.MAX_MODES) {
            throw new IllegalArgumentException("at most " + KeyHook.MAX_MODES + " modes, got " + modes.size());
        }
        table = List.copyOf(modes);
        if (hook != null) {
            KeyHook target = hook;
            List<KeyHook.Mode> wire = wireTable(table);
            send(() -> target.table(wire));
        }
    }

    /**
     * Picks the mode of the table in force. The hook moves between modes by itself as rules say; this sets it from
     * the plugin's side, as when the chat gains or loses the keyboard.
     *
     * @param mode the mode's index in the table
     */
    public synchronized void setRemapMode(int mode) {
        this.mode = mode;
        if (hook != null) {
            KeyHook target = hook;
            send(() -> target.mode(mode));
        }
    }

    /**
     * The mode last set with {@link #setRemapMode}.
     *
     * @return the mode's index
     */
    public synchronized int getRemapMode() {
        return mode;
    }

    /**
     * Starts telling a listener what the hook does with each key message, on the client thread. The hook only
     * reports while a listener is registered.
     *
     * @param listener the listener
     */
    public void addKeyReportListener(Consumer<KeyReport> listener) {
        boolean first = reportListeners.isEmpty();
        reportListeners.add(listener);
        if (first) {
            sendReporting(true);
        }
    }

    /**
     * Stops telling a listener about key messages.
     *
     * @param listener the listener
     */
    public void removeKeyReportListener(Consumer<KeyReport> listener) {
        reportListeners.remove(listener);
        if (reportListeners.isEmpty()) {
            sendReporting(false);
        }
    }

    /**
     * Uses a new connection to the hook for the remapping, sending the table, mode and reporting through it, or drops
     * the connection. Internal: the client's native hook calls this as its connection comes up and drops.
     *
     * @param hook the connection, or null while there is none
     */
    public synchronized void setKeyHook(KeyHook hook) {
        this.hook = hook;
        this.sendFailed = false;
        if (hook == null) {
            return;
        }
        List<KeyHook.Mode> wire = wireTable(table);
        int current = mode;
        boolean reporting = !reportListeners.isEmpty();
        send(() -> {
            hook.table(wire);
            hook.mode(current);
            hook.report(reporting);
        });
    }

    /**
     * Delivers a report from the hook to the listeners on the client thread. Internal: the hook server calls this on
     * its reader thread.
     *
     * @param report the report
     */
    public void onKeyReport(KeyReport report) {
        if (reportListeners.isEmpty()) {
            return;
        }
        Runnable deliver = () -> {
            for (Consumer<KeyReport> listener : reportListeners) {
                try {
                    listener.accept(report);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Key report listener failed", e);
                }
            }
        };
        if (clientThread != null) {
            clientThread.invoke(deliver);
        } else {
            deliver.run();
        }
    }

    /**
     * Updates each listener from the keys held now.
     *
     * @return true once there is no listener left, which ends the polling
     */
    boolean poll() {
        if (listeners.isEmpty()) {
            return true;
        }
        boolean hasKeyboard = focused.getAsBoolean();
        for (HotkeyListener listener : listeners) {
            Keybind keybind = listener.keybind();
            boolean down = hasKeyboard && held(keybind) && (listener.isPressed() || noOtherModifier(keybind));
            listener.update(down);
        }
        return false;
    }

    private synchronized void sendReporting(boolean on) {
        if (hook != null) {
            KeyHook target = hook;
            send(() -> target.report(on));
        }
    }

    private void send(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (!sendFailed) {
                sendFailed = true;
                log.log(Level.WARNING, "Could not send the key remapping to the native hook", e);
            }
        }
    }

    /**
     * Turns a table into the hook's: Windows virtual-key codes and modifier bits. A rule whose key has no Windows
     * code is left out.
     *
     * @param modes the table
     * @return the wire table
     */
    static List<KeyHook.Mode> wireTable(List<KeyRemapMode> modes) {
        List<KeyHook.Mode> wire = new ArrayList<>();
        for (KeyRemapMode mode : modes) {
            List<KeyHook.Rule> rules = new ArrayList<>();
            for (KeyRemap remap : mode.remaps()) {
                KeyHook.Rule rule = wireRule(remap);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            wire.add(new KeyHook.Mode(mode.blockTyping(), rules));
        }
        return wire;
    }

    /**
     * Turns one rule into the hook's.
     *
     * @param remap the rule
     * @return the wire rule, or null if its key has no Windows code
     */
    static KeyHook.Rule wireRule(KeyRemap remap) {
        int vk = remap.keyCode() == KeyEvent.VK_UNDEFINED ? modifierKey(remap.modifiers()) : toWindows(remap.keyCode());
        if (vk <= 0) {
            return null;
        }
        int to;
        if (remap.toKeyCode() == KeyRemap.BLOCK) {
            to = KeyHook.BLOCK;
        } else {
            to = toWindows(remap.toKeyCode());
            if (to <= 0) {
                return null;
            }
        }
        boolean ignore = remap.ignoreModifiers() || remap.keyCode() == KeyEvent.VK_UNDEFINED;
        int modifiers = 0;
        if ((remap.modifiers() & InputEvent.SHIFT_DOWN_MASK) != 0) {
            modifiers |= KeyHook.SHIFT;
        }
        if ((remap.modifiers() & InputEvent.CTRL_DOWN_MASK) != 0) {
            modifiers |= KeyHook.CTRL;
        }
        if ((remap.modifiers() & InputEvent.ALT_DOWN_MASK) != 0) {
            modifiers |= KeyHook.ALT;
        }
        int next = remap.nextMode() == KeyRemap.STAY ? KeyHook.STAY : remap.nextMode();
        return new KeyHook.Rule(vk, ignore ? 0 : modifiers, ignore ? 0 : KeyHook.SHIFT | KeyHook.CTRL | KeyHook.ALT,
                to, next);
    }

    /**
     * The virtual-key code of a modifier on its own.
     *
     * @param modifiers an {@link InputEvent} modifier mask
     * @return the key, or 0 for none
     */
    private static int modifierKey(int modifiers) {
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            return VK_SHIFT;
        }
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            return VK_CONTROL;
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            return VK_MENU;
        }
        return 0;
    }

    private void startPolling() {
        if (!polling) {
            polling = true;
            clientThread.invokeLater(() -> {
                boolean done = poll();
                if (done) {
                    polling = false;
                }
                return done;
            });
        }
    }

    private boolean held(Keybind keybind) {
        List<Integer> keys = keybind.getKeyCodes();
        if (keys.isEmpty()) {
            return false;
        }
        for (int key : keys) {
            if (!down(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether no modifier outside the keybind's is held, so Ctrl+Alt doesn't count as Alt going down.
     *
     * @param keybind the keybind
     * @return true if only its own modifiers are held
     */
    private boolean noOtherModifier(Keybind keybind) {
        for (int mask : MODIFIERS) {
            if ((keybind.getModifiers() & mask) == 0 && keybind.getKeyCode() != keyFor(mask) && down(keyFor(mask))) {
                return false;
            }
        }
        return true;
    }

    private boolean down(int keyCode) {
        int vk = toWindows(keyCode);
        return vk > 0 && windowsKeyDown.test(vk);
    }

    private static int keyFor(int modifierMask) {
        return switch (modifierMask) {
            case InputEvent.CTRL_DOWN_MASK -> KeyEvent.VK_CONTROL;
            case InputEvent.ALT_DOWN_MASK -> KeyEvent.VK_ALT;
            case InputEvent.SHIFT_DOWN_MASK -> KeyEvent.VK_SHIFT;
            default -> KeyEvent.VK_META;
        };
    }

    /**
     * The Windows virtual-key code of a {@link KeyEvent} key code. Letters, digits, the function keys up to F12,
     * arrows, the numeric keypad and most control keys share their codes; the rest are mapped here.
     *
     * @param keyCode a {@link KeyEvent} key code
     * @return the virtual-key code, or 0 for a key with none
     */
    public static int toWindows(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UNDEFINED -> 0;
            case KeyEvent.VK_ENTER -> 0x0D;
            case KeyEvent.VK_DELETE -> 0x2E;
            case KeyEvent.VK_INSERT -> 0x2D;
            case KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> 0x5B;
            case KeyEvent.VK_CONTEXT_MENU -> 0x5D;
            case KeyEvent.VK_PRINTSCREEN -> 0x2C;
            case KeyEvent.VK_SEMICOLON -> 0xBA;
            case KeyEvent.VK_EQUALS -> 0xBB;
            case KeyEvent.VK_COMMA -> 0xBC;
            case KeyEvent.VK_MINUS -> 0xBD;
            case KeyEvent.VK_PERIOD -> 0xBE;
            case KeyEvent.VK_SLASH -> 0xBF;
            case KeyEvent.VK_BACK_QUOTE -> 0xC0;
            case KeyEvent.VK_OPEN_BRACKET -> 0xDB;
            case KeyEvent.VK_BACK_SLASH -> 0xDC;
            case KeyEvent.VK_CLOSE_BRACKET -> 0xDD;
            case KeyEvent.VK_QUOTE -> 0xDE;
            case KeyEvent.VK_NUM_LOCK -> 0x90;
            case KeyEvent.VK_SCROLL_LOCK -> 0x91;
            default -> {
                if (keyCode >= KeyEvent.VK_F13 && keyCode <= KeyEvent.VK_F24) {
                    yield 0x7C + keyCode - KeyEvent.VK_F13;
                }
                yield keyCode <= 0xFE ? keyCode : 0;
            }
        };
    }

    /**
     * The {@link KeyEvent} key code of a Windows virtual-key code, the inverse of {@link #toWindows}.
     *
     * @param vk a virtual-key code
     * @return the key code, or 0 for a key with none
     */
    public static int toJava(int vk) {
        return switch (vk) {
            case 0x0D -> KeyEvent.VK_ENTER;
            case 0x2E -> KeyEvent.VK_DELETE;
            case 0x2D -> KeyEvent.VK_INSERT;
            case 0x5B, 0x5C -> KeyEvent.VK_WINDOWS;
            case 0x5D -> KeyEvent.VK_CONTEXT_MENU;
            case 0x2C -> KeyEvent.VK_PRINTSCREEN;
            case 0xBA -> KeyEvent.VK_SEMICOLON;
            case 0xBB -> KeyEvent.VK_EQUALS;
            case 0xBC -> KeyEvent.VK_COMMA;
            case 0xBD -> KeyEvent.VK_MINUS;
            case 0xBE -> KeyEvent.VK_PERIOD;
            case 0xBF -> KeyEvent.VK_SLASH;
            case 0xC0 -> KeyEvent.VK_BACK_QUOTE;
            case 0xDB -> KeyEvent.VK_OPEN_BRACKET;
            case 0xDC -> KeyEvent.VK_BACK_SLASH;
            case 0xDD -> KeyEvent.VK_CLOSE_BRACKET;
            case 0xDE -> KeyEvent.VK_QUOTE;
            case 0x90 -> KeyEvent.VK_NUM_LOCK;
            case 0x91 -> KeyEvent.VK_SCROLL_LOCK;
            default -> {
                if (vk >= 0x7C && vk <= 0x87) {
                    yield KeyEvent.VK_F13 + vk - 0x7C;
                }
                yield vk > 0 && vk <= 0xFE ? vk : 0;
            }
        };
    }
}
