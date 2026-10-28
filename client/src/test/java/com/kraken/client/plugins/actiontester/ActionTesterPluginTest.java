package com.kraken.client.plugins.actiontester;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How {@link ActionTesterPlugin} works an op out of an option and reads a component setting.
 */
class ActionTesterPluginTest {

    @Test
    void anOptionIsMatchedByTextOrTakenAsANumber() {
        String[] actions = {"Talk-to", null, "Attack", "Pickpocket", null};
        assertEquals(3, ActionTesterPlugin.opOf(actions, " attack ", 5));
        assertEquals(1, ActionTesterPlugin.opOf(actions, "TALK-TO", 5));
        assertEquals(4, ActionTesterPlugin.opOf(actions, "4", 5));
        assertEquals(-1, ActionTesterPlugin.opOf(actions, "Trade", 5), "no such option");
        assertEquals(-1, ActionTesterPlugin.opOf(actions, "6", 5), "past the subject's ops");
        assertEquals(-1, ActionTesterPlugin.opOf(null, "Attack", 5), "no actions known");
        assertEquals(2, ActionTesterPlugin.opOf(null, "2", 8), "a number needs no actions");
    }

    @Test
    void aComponentIsGroupChildAndAnOptionalSlot() {
        assertArrayEquals(new int[]{(149 << 16), -1}, ActionTesterPlugin.parseComponent("149:0"));
        assertArrayEquals(new int[]{(149 << 16) | 0, 3}, ActionTesterPlugin.parseComponent(" 149 : 0 : 3 "));
        assertArrayEquals(new int[]{(231 << 16) | 5, -1}, ActionTesterPlugin.parseComponent(String.valueOf((231 << 16) | 5)));
        assertNull(ActionTesterPlugin.parseComponent("Moss giant"));
        assertNull(ActionTesterPlugin.parseComponent(""));
        assertNull(ActionTesterPlugin.parseComponent("149:x"));
        assertNull(ActionTesterPlugin.parseComponent("70000:1"), "a group id is 16 bits");
    }
}
