package com.kraken.client.input;

import com.kraken.client.plugins.config.Keybind;
import com.kraken.client.plugins.config.ModifierlessKeybind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key manager over a fake keyboard: hotkeys go down and up with their keys while the game has the keyboard, extra
 * modifiers keep one from going down, and Java key codes become Windows virtual-key codes.
 */
class KeyManagerTest {

    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;
    private static final int VK_F = 0x46;

    private final Set<Integer> held = new HashSet<>();
    private final List<String> heard = new ArrayList<>();
    private boolean focused = true;
    private KeyManager keys;

    @BeforeEach
    void setUp() {
        keys = new KeyManager(() -> focused, null, held::contains);
    }

    private HotkeyListener listener(Keybind keybind) {
        return new HotkeyListener(() -> keybind) {
            @Override
            public void hotkeyPressed() {
                heard.add("down");
            }

            @Override
            public void hotkeyReleased() {
                heard.add("up");
            }
        };
    }

    @Test
    void aHotkeyGoesDownAndUpWithItsKeys() {
        HotkeyListener alt = listener(Keybind.ALT);
        keys.registerHotkeyListener(alt);
        keys.poll();
        assertEquals(List.of(), heard);
        held.add(VK_MENU);
        keys.poll();
        keys.poll();
        assertEquals(List.of("down"), heard, "once per press");
        assertTrue(alt.isPressed());
        held.add(VK_SHIFT);
        keys.poll();
        assertEquals(List.of("down"), heard, "another modifier joining doesn't let it go");
        held.remove(VK_MENU);
        keys.poll();
        assertEquals(List.of("down", "up"), heard);
    }

    @Test
    void anotherModifierHeldFirstKeepsItUp() {
        keys.registerHotkeyListener(listener(Keybind.ALT));
        held.add(VK_CONTROL);
        held.add(VK_MENU);
        keys.poll();
        assertEquals(List.of(), heard, "Ctrl+Alt isn't Alt");
        HotkeyListener ctrlF = listener(new Keybind(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        keys.registerHotkeyListener(ctrlF);
        held.remove(VK_MENU);
        held.add(VK_F);
        keys.poll();
        assertTrue(ctrlF.isPressed(), "Ctrl+F needs both");
    }

    @Test
    void losingTheKeyboardLetsItGoAndUnsetNeverGoesDown() {
        keys.registerHotkeyListener(listener(Keybind.SHIFT));
        keys.registerHotkeyListener(listener(Keybind.NOT_SET));
        held.add(VK_SHIFT);
        keys.poll();
        focused = false;
        keys.poll();
        assertEquals(List.of("down", "up"), heard);
        assertFalse(keys.isKeyPressed(KeyEvent.VK_SHIFT), "keys don't count while another window has them");
        focused = true;
        assertTrue(keys.isKeyPressed(KeyEvent.VK_SHIFT));
        assertTrue(keys.isPressed(Keybind.SHIFT));
        assertFalse(keys.isPressed(Keybind.NOT_SET));
    }

    @Test
    void pollingEndsWithTheLastListener() {
        HotkeyListener alt = listener(Keybind.ALT);
        keys.registerHotkeyListener(alt);
        assertFalse(keys.poll());
        keys.unregisterHotkeyListener(alt);
        assertTrue(keys.poll());
    }

    @Test
    void aRemapTableBecomesWindowsRulesAndReachesTheHookOnceConnected() {
        List<String> sent = new ArrayList<>();
        KeyHook hook = new KeyHook() {
            @Override
            public void table(List<Mode> modes) {
                sent.add("table " + modes);
            }

            @Override
            public void mode(int mode) {
                sent.add("mode " + mode);
            }

            @Override
            public void report(boolean on) {
                sent.add("report " + on);
            }
        };
        List<KeyRemapMode> table = List.of(KeyRemapMode.PASS, new KeyRemapMode(true, List.of(
                KeyRemap.of(new ModifierlessKeybind(KeyEvent.VK_W, 0), KeyEvent.VK_UP),
                KeyRemap.of(new Keybind(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), KeyEvent.VK_F1),
                KeyRemap.of(Keybind.CTRL, KeyEvent.VK_CONTROL),
                KeyRemap.transition(KeyEvent.VK_ENTER, 2),
                KeyRemap.block(KeyEvent.VK_ESCAPE, 1),
                KeyRemap.of(new Keybind(KeyEvent.VK_UNDEFINED, 0), KeyEvent.VK_F2))));
        assertFalse(keys.isRemappingAvailable());
        keys.setRemapTable(table);
        keys.setRemapMode(1);
        assertEquals(List.of(), sent, "kept until a hook connects");

        keys.setKeyHook(hook);
        assertTrue(keys.isRemappingAvailable());
        List<KeyHook.Mode> wire = KeyManager.wireTable(table);
        assertEquals(List.of("table " + wire, "mode 1", "report false"), sent);
        assertEquals(List.of(), wire.get(0).rules());
        assertTrue(wire.get(1).blockTyping());
        assertEquals(List.of(
                new KeyHook.Rule(0x57, 0, 0, 0x26, KeyHook.STAY),
                new KeyHook.Rule(0x31, KeyHook.CTRL, 7, 0x70, KeyHook.STAY),
                new KeyHook.Rule(0x11, 0, 0, 0x11, KeyHook.STAY),
                new KeyHook.Rule(0x0D, 0, 0, 0x0D, 2),
                new KeyHook.Rule(0x1B, 0, 0, KeyHook.BLOCK, 1)), wire.get(1).rules(),
                "a keybind with no key makes no rule");

        sent.clear();
        keys.setRemapMode(2);
        List<KeyReport> reports = new ArrayList<>();
        keys.addKeyReportListener(reports::add);
        KeyReport report = new KeyReport(KeyReport.Kind.PRESSED, KeyEvent.VK_ENTER, (char) 0, KeyReport.Action.PASSED,
                0, 1, 2);
        keys.onKeyReport(report);
        assertEquals(List.of(report), reports, "delivered straight when there is no client thread");
        keys.removeKeyReportListener(reports::add);
        assertEquals(List.of("mode 2", "report true"), sent);
        assertThrows(IllegalArgumentException.class, () -> keys.setRemapTable(java.util.Collections.nCopies(9,
                KeyRemapMode.PASS)));
    }

    @Test
    void windowsKeyCodesBecomeJavaOnes() {
        assertEquals(KeyEvent.VK_ENTER, KeyManager.toJava(0x0D));
        assertEquals(KeyEvent.VK_COMMA, KeyManager.toJava(0xBC));
        assertEquals(KeyEvent.VK_F13, KeyManager.toJava(0x7C));
        assertEquals(KeyEvent.VK_W, KeyManager.toJava(0x57));
        assertEquals(0, KeyManager.toJava(0));
        for (int code : new int[]{KeyEvent.VK_A, KeyEvent.VK_F1, KeyEvent.VK_UP, KeyEvent.VK_ESCAPE, KeyEvent.VK_SPACE,
                KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_CONTROL}) {
            assertEquals(code, KeyManager.toJava(KeyManager.toWindows(code)), "round trip of " + code);
        }
    }

    @Test
    void javaKeyCodesBecomeWindowsOnes() {
        assertEquals(VK_F, KeyManager.toWindows(KeyEvent.VK_F));
        assertEquals(VK_MENU, KeyManager.toWindows(KeyEvent.VK_ALT));
        assertEquals(0x0D, KeyManager.toWindows(KeyEvent.VK_ENTER));
        assertEquals(0x2E, KeyManager.toWindows(KeyEvent.VK_DELETE));
        assertEquals(0xBC, KeyManager.toWindows(KeyEvent.VK_COMMA));
        assertEquals(0x7C, KeyManager.toWindows(KeyEvent.VK_F13));
        assertEquals(0x70, KeyManager.toWindows(KeyEvent.VK_F1));
        assertEquals(0, KeyManager.toWindows(KeyEvent.VK_UNDEFINED));
    }
}
