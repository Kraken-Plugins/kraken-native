package com.kraken.client.plugins.actiontester;

import com.kraken.api.gameval.InterfaceID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How {@link ActionTesterPlugin} works an op and a sub-option out of an option, reads a component setting and finds
 * a spell's component.
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

    @Test
    void anOptionSplitsAtItsLastDotIntoOpAndSubOption() {
        assertArrayEquals(new String[]{"Drop", "All"}, ActionTesterPlugin.splitOption("Drop.All"));
        assertArrayEquals(new String[]{"3", "2"}, ActionTesterPlugin.splitOption(" 3.2 "));
        assertArrayEquals(new String[]{"Withdraw-X", "All but one"}, ActionTesterPlugin.splitOption("Withdraw-X.All but one"));
        assertArrayEquals(new String[]{"Attack", null}, ActionTesterPlugin.splitOption("Attack"));
        assertArrayEquals(new String[]{"Drop.", null}, ActionTesterPlugin.splitOption("Drop."), "nothing after the dot");
        assertArrayEquals(new String[]{".All", null}, ActionTesterPlugin.splitOption(".All"), "nothing before the dot");
        assertEquals(2, ActionTesterPlugin.opOf(new String[]{"One", "All"}, "all", 2), "a sub-option matches like an op");
    }

    @Test
    void aSpellIsAComponentOrASpellbookConstantsName() {
        assertEquals(InterfaceID.MagicSpellbook.HIGH_ALCHEMY, ActionTesterPlugin.spellComponent("High Alchemy"));
        assertEquals(InterfaceID.MagicSpellbook.HIGH_ALCHEMY, ActionTesterPlugin.spellComponent(" high-alchemy "));
        assertEquals(InterfaceID.MagicSpellbook.WIND_STRIKE, ActionTesterPlugin.spellComponent("wind_strike"));
        assertEquals((218 << 16) | 47, ActionTesterPlugin.spellComponent("218:47"), "a component as given");
        assertEquals(-1, ActionTesterPlugin.spellComponent("Moss giant"));
        assertEquals(-1, ActionTesterPlugin.spellComponent(""));
    }
}
