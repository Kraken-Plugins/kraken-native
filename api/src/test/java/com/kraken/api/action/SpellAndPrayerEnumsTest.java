package com.kraken.api.action;

import com.kraken.api.gameval.InterfaceID;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Spell}, {@link Spellbook} and {@link Prayer} constants: each names a component of its interface, distinct
 * from the others, and the varbits the prayers and spellbooks read are distinct too.
 */
class SpellAndPrayerEnumsTest {

    @Test
    void everySpellIsASpellbookComponentAndDistinct() {
        int spellbookGroup = InterfaceID.MagicSpellbook.UNIVERSE >>> 16;
        Set<Integer> components = new HashSet<>();
        for (Spell spell : Spell.values()) {
            assertEquals(spellbookGroup, spell.getComponentId() >>> 16, spell + " is in the spellbook interface");
            assertTrue(components.add(spell.getComponentId()), spell + " shares its component");
        }
        assertEquals(Spell.values().length, components.size());
    }

    @Test
    void everySpellbookHasSpells() {
        EnumSet<Spellbook> books = EnumSet.noneOf(Spellbook.class);
        for (Spell spell : Spell.values()) {
            books.add(spell.getSpellbook());
        }
        assertEquals(EnumSet.allOf(Spellbook.class), books);
    }

    @Test
    void spellbookIdsRoundTrip() {
        for (Spellbook book : Spellbook.values()) {
            assertEquals(book, Spellbook.of(book.getId()));
        }
        assertNull(Spellbook.of(-1));
        assertNull(Spellbook.of(4));
    }

    @Test
    void everyPrayerIsAPrayerBookComponentAndDistinct() {
        int prayerBookGroup = InterfaceID.PRAYERBOOK;
        Set<Integer> components = new HashSet<>();
        Set<Integer> varbits = new HashSet<>();
        for (Prayer prayer : Prayer.values()) {
            assertEquals(prayerBookGroup, prayer.getComponentId() >>> 16, prayer + " is in the prayer book");
            assertTrue(components.add(prayer.getComponentId()), prayer + " shares its component");
            assertTrue(varbits.add(prayer.getVarbit()), prayer + " shares its varbit");
        }
        assertEquals(29, Prayer.values().length);
    }
}
