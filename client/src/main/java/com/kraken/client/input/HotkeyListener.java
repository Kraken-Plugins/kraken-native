package com.kraken.client.input;

import com.kraken.client.plugins.config.Keybind;
import lombok.Getter;

import java.util.function.Supplier;

/**
 * Hears a {@link Keybind} being held down and let go while the game has the keyboard. Register it with
 * {@link KeyManager#registerHotkeyListener}; both methods run on the client thread.
 */
public abstract class HotkeyListener {

    private final Supplier<Keybind> keybind;

    /**
     * Whether the keybind is held now, as of the last poll. Client thread only.
     */
    @Getter
    private boolean pressed;

    /**
     * A listener for a keybind, read each poll so a settings change takes effect at once.
     *
     * @param keybind the keybind, such as a config getter
     */
    protected HotkeyListener(Supplier<Keybind> keybind) {
        this.keybind = keybind;
    }

    /**
     * The keybind went down: all its keys are held, and no other modifier.
     */
    public void hotkeyPressed() {
    }

    /**
     * The keybind was let go: one of its keys was released, or the game lost the keyboard.
     */
    public void hotkeyReleased() {
    }

    /**
     * The keybind this listens for now.
     *
     * @return the keybind; {@link Keybind#NOT_SET} never goes down
     */
    Keybind keybind() {
        Keybind current = keybind.get();
        return current != null ? current : Keybind.NOT_SET;
    }

    /**
     * Moves to the state the keys are in, calling {@link #hotkeyPressed} or {@link #hotkeyReleased} on a change.
     *
     * @param down whether the keybind counts as held now
     */
    void update(boolean down) {
        if (down && !pressed) {
            pressed = true;
            hotkeyPressed();
        } else if (!down && pressed) {
            pressed = false;
            hotkeyReleased();
        }
    }
}
