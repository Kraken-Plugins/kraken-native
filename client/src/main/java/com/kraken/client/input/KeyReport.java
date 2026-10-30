package com.kraken.client.input;

/**
 * What the native hook did with one key message, reported while a {@link KeyManager#addKeyReportListener} listener
 * is registered: the key or character the message carried, whether it passed, was remapped or dropped, and the mode
 * before and after it. Delivered on the client thread.
 *
 * @param kind       which message
 * @param keyCode    the {@link java.awt.event.KeyEvent} key code the message carried, for a press or release; 0 for
 *                   a key with no Java code
 * @param keyChar    the character, for a typed character; 0 otherwise
 * @param action     what the hook did
 * @param toKeyCode  the key it became, for a remap; 0 otherwise
 * @param modeBefore the mode in force when the message arrived
 * @param modeAfter  the mode after it, which a rule may have moved to
 */
public record KeyReport(Kind kind, int keyCode, char keyChar, Action action, int toKeyCode, int modeBefore,
                        int modeAfter) {

    /**
     * The key messages the hook handles.
     */
    public enum Kind {
        PRESSED, RELEASED, TYPED
    }

    /**
     * What the hook did with a message.
     */
    public enum Action {
        PASSED, REMAPPED, BLOCKED
    }
}
