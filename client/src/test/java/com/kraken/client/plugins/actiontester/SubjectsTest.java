package com.kraken.client.plugins.actiontester;

import com.kraken.api.MenuAction;
import com.kraken.api.MenuEntry;
import com.kraken.api.gameval.InterfaceID;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link Subjects} reads the Action Tester's settings: a component, a spell or a prayer, an option split into its
 * op and sub-option, and a menu entry by its row or its text.
 */
class SubjectsTest {

    @Test
    void aComponentIsGroupChildAndAnOptionalSlot() {
        assertArrayEquals(new int[]{(149 << 16), -1}, Subjects.parseComponent("149:0"));
        assertArrayEquals(new int[]{(149 << 16), 3}, Subjects.parseComponent(" 149:0:3 "));
        assertArrayEquals(new int[]{(231 << 16) | 5, -1}, Subjects.parseComponent(String.valueOf((231 << 16) | 5)));
        assertNull(Subjects.parseComponent("Moss giant"));
        assertNull(Subjects.parseComponent(""));
        assertNull(Subjects.parseComponent("149:x"));
        assertNull(Subjects.parseComponent("70000:1"), "a group id is 16 bits");
    }

    @Test
    void aComponentCanBeNamedAsInInterfaceId() {
        assertArrayEquals(new int[]{InterfaceID.Orbs.RUNBUTTON, -1}, Subjects.component("Orbs.RUNBUTTON"));
        assertArrayEquals(new int[]{InterfaceID.Orbs.RUNBUTTON, -1}, Subjects.component(" orbs.runbutton "));
        assertArrayEquals(new int[]{InterfaceID.Prayerbook.PRAYER19, -1},
                Subjects.component("InterfaceID.Prayerbook.PRAYER19 Protect from Melee"));
        assertArrayEquals(new int[]{(149 << 16), 3}, Subjects.component("149:0:3"), "numbers still work");
        assertNull(Subjects.component("Orbs.NOSUCHBUTTON"));
        assertNull(Subjects.component("Nosuchgroup.RUNBUTTON"));
        assertNull(Subjects.component("Drop.All"));
    }

    @Test
    void aComponentSuggestionReadsBackAsItsComponent() {
        assertArrayEquals(new int[]{(149 << 16), 3}, Subjects.parseComponent("149:0:3 Coins"));
        assertArrayEquals(new int[]{(161 << 16) | 65, -1}, Subjects.parseComponent("161:65 Quick-prayers"));
    }

    @Test
    void anOptionSplitsAtItsLastDotIntoOpAndSubOption() {
        assertArrayEquals(new String[]{"Drop", "All"}, Subjects.splitOption("Drop.All"));
        assertArrayEquals(new String[]{"3", "2"}, Subjects.splitOption(" 3.2 "));
        assertArrayEquals(new String[]{"Withdraw-X", "All but one"}, Subjects.splitOption("Withdraw-X.All but one"));
        assertArrayEquals(new String[]{"Attack", null}, Subjects.splitOption("Attack"));
        assertArrayEquals(new String[]{"Drop.", null}, Subjects.splitOption("Drop."), "nothing after the dot");
        assertArrayEquals(new String[]{".All", null}, Subjects.splitOption(".All"), "nothing before the dot");
    }

    @Test
    void aNumberSettingIsTakenAsAnOpAndAnythingElseIsText() {
        assertEquals(4, Subjects.number(" 4 "));
        assertEquals(-1, Subjects.number("Attack"));
        assertEquals(-1, Subjects.number(""));
    }

    @Test
    void aSpellIsAComponentOrASpellbookConstantsName() {
        assertEquals(InterfaceID.MagicSpellbook.HIGH_ALCHEMY, spell("High Alchemy"));
        assertEquals(InterfaceID.MagicSpellbook.HIGH_ALCHEMY, spell(" high-alchemy "));
        assertEquals(InterfaceID.MagicSpellbook.WIND_STRIKE, spell("wind_strike"));
        assertEquals((218 << 16) | 47, spell("218:47"), "a component as given");
        assertEquals(-1, spell("Moss giant"));
        assertEquals(-1, spell(""));
    }

    @Test
    void everySpellSuggestionReadsBackAsItsComponent() throws IllegalAccessException {
        assertEquals("High Alchemy", Subjects.constantName("HIGH_ALCHEMY"));
        for (Field field : InterfaceID.MagicSpellbook.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                assertEquals(field.getInt(null), spell(Subjects.constantName(field.getName())),
                        field.getName());
            }
        }
    }

    @Test
    void aPrayerIsItsPlaceInTheBook() {
        assertEquals(InterfaceID.Prayerbook.PRAYER19,
                Subjects.constantComponent(InterfaceID.Prayerbook.class, "Prayer 19"));
        assertEquals(InterfaceID.Prayerbook.PRAYER1, Subjects.constantComponent(InterfaceID.Prayerbook.class,
                Subjects.constantName("PRAYER1")), "the fallback suggestion reads back");
        assertEquals(-1, Subjects.constantComponent(InterfaceID.Prayerbook.class, "Protect from Melee"),
                "a prayer's shown name needs the game");
        assertTrue(Subjects.isPrayer("PRAYER30"));
        assertFalse(Subjects.isPrayer("FILTERBUTTON"));
    }

    @Test
    void aMenuEntryIsItsRowFromTheTopOrItsText() {
        MenuEntry attack = new MenuEntry(null, "Attack", "<col=ffff00>Goblin<col=ff00>  (level-2)", 5,
                MenuAction.NPC_SECOND_OPTION, 0, 0, null);
        MenuEntry walk = new MenuEntry(null, "Walk here", "", 0, MenuAction.WALK, 50, 51, null);
        MenuEntry[] rows = {attack, walk};
        assertEquals("Attack Goblin (level-2)", Subjects.menuText(attack), "markup and doubled spaces go");
        assertEquals("Walk here", Subjects.menuText(walk));
        assertSame(attack, Subjects.menuEntry(rows, "0"));
        assertSame(walk, Subjects.menuEntry(rows, " 1 "));
        assertSame(attack, Subjects.menuEntry(rows, "attack goblin (level-2)"));
        assertSame(walk, Subjects.menuEntry(rows, "WALK HERE"));
        assertNull(Subjects.menuEntry(rows, "2"), "past the last row");
        assertNull(Subjects.menuEntry(rows, "Talk-to Goblin"));
    }

    @Test
    void textLosesTheClientsMarkup() {
        assertEquals("Rune platebody", Subjects.plain("<col=ff9040>Rune platebody</col>"));
        assertEquals("", Subjects.plain(null));
        assertEquals("a b", Subjects.plain(" a <br> b "));
    }

    /**
     * A spell's component as the spell setting reads a constant's name.
     *
     * @param text the setting's text
     * @return the packed component id, or -1
     */
    private static int spell(String text) {
        return Subjects.constantComponent(InterfaceID.MagicSpellbook.class, text);
    }
}
