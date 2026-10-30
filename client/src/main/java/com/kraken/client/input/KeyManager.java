package com.kraken.client.input;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.Client;
import com.kraken.client.callback.ClientThread;
import com.kraken.client.plugins.config.Keybind;
import com.sun.jna.platform.win32.User32;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * Tells plugins which keys are held while the game has the keyboard. The game reads the keyboard itself, so Kraken
 * gets none of its key events: the manager reads the keys' state from Windows instead, and only counts keys while
 * {@link Client#isFocused()}. It can't stop the game from seeing a key.
 *
 * <p>{@link HotkeyListener}s are polled on the client thread at each pass, about every 20 ms, while any is
 * registered, so a press shorter than that can be missed. {@link #isKeyPressed} and {@link #isPressed} read the keys
 * when called, from any thread. Key codes are {@link KeyEvent}'s.
 */
@Singleton
public class KeyManager {

    private static final int[] MODIFIERS = {InputEvent.CTRL_DOWN_MASK, InputEvent.ALT_DOWN_MASK,
            InputEvent.SHIFT_DOWN_MASK, InputEvent.META_DOWN_MASK};

    private final BooleanSupplier focused;
    private final ClientThread clientThread;
    private final IntPredicate windowsKeyDown;
    private final List<HotkeyListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Whether the poll task is queued. Client thread only.
     */
    private boolean polling;

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
    static int toWindows(int keyCode) {
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
}
