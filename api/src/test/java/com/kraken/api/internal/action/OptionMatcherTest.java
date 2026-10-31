package com.kraken.api.internal.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OptionMatcher}: markup, case, spaces, hyphens and underscores, numbers and their limits, empty and "null"
 * slots.
 */
class OptionMatcherTest {

    private static final String[] NPC = {"Talk-to", null, "Attack", "Pickpocket", "null"};

    @Test
    void textIsMatchedIgnoringCaseAndSeparators() {
        assertEquals(1, OptionMatcher.match(NPC, "Talk-to", 5));
        assertEquals(1, OptionMatcher.match(NPC, "talk to", 5));
        assertEquals(1, OptionMatcher.match(NPC, "TALK_TO", 5));
        assertEquals(1, OptionMatcher.match(NPC, "  talk -  to ", 5), "runs of separators count as one");
        assertEquals(3, OptionMatcher.match(NPC, "attack", 5));
        assertEquals(-1, OptionMatcher.match(NPC, "Atack", 5));
        assertEquals(-1, OptionMatcher.match(NPC, "Talkto", 5), "a separator isn't nothing");
    }

    @Test
    void markupIsRemovedOnBothSides() {
        String[] options = {"<col=ff9040>Chop-down</col>", "Knock<lt>3"};
        assertEquals(1, OptionMatcher.match(options, "chop down", 5));
        assertEquals(1, OptionMatcher.match(new String[]{"Chop down"}, "<col=ffffff>Chop-Down</col>", 5));
        assertEquals(2, OptionMatcher.match(options, "knock<3", 5), "the escape stands for the bracket");
        assertEquals("Knock<3", OptionMatcher.plain("<col=ff0000>Knock</col><lt>3"));
    }

    @Test
    void theFirstMatchInOpOrderWins() {
        assertEquals(2, OptionMatcher.match(new String[]{"Use", "Drop", "drop"}, "DROP", 10));
    }

    @Test
    void emptyAndNullSlotsNeverMatch() {
        assertEquals(-1, OptionMatcher.match(NPC, "null", 5));
        assertEquals(-1, OptionMatcher.match(NPC, "", 5));
        assertEquals(-1, OptionMatcher.match(new String[]{"", " "}, " ", 5));
        assertEquals(-1, OptionMatcher.match(null, "Attack", 5), "unknown options match nothing");
        assertFalse(OptionMatcher.isNamed("<col=ffffff></col>"));
        assertFalse(OptionMatcher.isNamed("NULL"));
        assertTrue(OptionMatcher.isNamed("Take"));
    }

    @Test
    void opsPastTheLimitAreNotMatched() {
        assertEquals(-1, OptionMatcher.match(NPC, "Pickpocket", 3));
        assertEquals(4, OptionMatcher.match(NPC, "Pickpocket", 5));
    }

    @Test
    void numbersCountFromOneUpToTheLimitAndNeedANamedSlot() {
        assertTrue(OptionMatcher.isOp(NPC, 1, 5));
        assertFalse(OptionMatcher.isOp(NPC, 2, 5), "an empty slot");
        assertFalse(OptionMatcher.isOp(NPC, 5, 5), "the \"null\" slot");
        assertFalse(OptionMatcher.isOp(NPC, 0, 5));
        assertFalse(OptionMatcher.isOp(NPC, 6, 5));
        assertFalse(OptionMatcher.isOp(new String[]{"Use"}, 2, 10), "past the end of the list");
        assertTrue(OptionMatcher.isOp(null, 8, 8), "options that can't be read don't block a number");
        assertFalse(OptionMatcher.isOp(null, 9, 8));
    }

    @Test
    void theFirstNamedOp() {
        assertEquals(3, OptionMatcher.first(new String[]{null, "null", "Use"}, 10));
        assertEquals(-1, OptionMatcher.first(new String[]{null, "Use"}, 1));
        assertEquals(-1, OptionMatcher.first(null, 10));
    }

    @Test
    void describeListsTheNamedOptions() {
        assertEquals("Talk-to, Attack, Pickpocket", OptionMatcher.describe(NPC));
        assertEquals("none", OptionMatcher.describe(new String[]{null, ""}));
        assertEquals("unknown", OptionMatcher.describe(null));
    }

    @Test
    void aNullOptionIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> OptionMatcher.match(NPC, null, 5));
    }
}
