package com.kraken.client.ui;

import com.google.inject.Singleton;

import java.awt.Color;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JColorChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Dialogs plugins ask the player something with: a line of text, a colour, or yes or no. The game's chatbox input
 * isn't written to, so each opens as a modal Swing dialog centred on Kraken's window, and the game keeps running
 * behind it. Safe to call from any thread: the dialog opens on the EDT and the answer comes back there. A cancelled
 * dialog calls nothing.
 */
@Singleton
public class Prompts {

    /**
     * Asks for a line of text.
     *
     * @param prompt what to ask, shown above the field, such as "Tile label"
     * @param value  the text the field starts with, or null for an empty field
     * @param onDone called on the EDT with the text as entered, possibly empty
     */
    public void textInput(String prompt, String value, Consumer<String> onDone) {
        Objects.requireNonNull(onDone, "onDone");
        SwingUtilities.invokeLater(() -> {
            Object answer = JOptionPane.showInputDialog(owner(), prompt, "Kraken", JOptionPane.PLAIN_MESSAGE, null,
                    null, value == null ? "" : value);
            if (answer != null) {
                onDone.accept(answer.toString());
            }
        });
    }

    /**
     * Asks for a colour, with its transparency.
     *
     * @param title    the dialog's title, such as "Tag color"
     * @param initial  the colour the picker starts on, or null for white
     * @param onPicked called on the EDT with the colour picked
     */
    public void pickColor(String title, Color initial, Consumer<Color> onPicked) {
        Objects.requireNonNull(onPicked, "onPicked");
        SwingUtilities.invokeLater(() -> {
            Color picked = JColorChooser.showDialog(owner(), title, initial == null ? Color.WHITE : initial, true);
            if (picked != null) {
                onPicked.accept(picked);
            }
        });
    }

    /**
     * Asks a yes or no question.
     *
     * @param message the question
     * @param onYes   called on the EDT if the answer is yes
     */
    public void confirm(String message, Runnable onYes) {
        Objects.requireNonNull(onYes, "onYes");
        SwingUtilities.invokeLater(() -> {
            int answer = JOptionPane.showConfirmDialog(owner(), message, "Are you sure?", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                onYes.run();
            }
        });
    }

    /**
     * The window a dialog is centred on: Kraken's main window while it shows, else the active window.
     *
     * @return the window, or null to centre on the screen
     */
    private static Window owner() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof KrakenFrame && frame.isShowing()) {
                return frame;
            }
        }
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    }
}
