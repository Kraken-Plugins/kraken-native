package com.kraken.client.plugins.grounditems;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How item list entries are read, and how a list matches an item: names with wildcards and quantity thresholds.
 */
class ItemThresholdTest {

    @Test
    void readsANameWithAQuantityAfterIt() {
        assertEquals(new ItemThreshold("Coins", 1000, ItemThreshold.Inequality.MORE_THAN, false),
                ItemThreshold.fromName("Coins > 1000"));
        assertEquals(new ItemThreshold("Shark", 5, ItemThreshold.Inequality.LESS_THAN, false),
                ItemThreshold.fromName("Shark<5"));
        assertEquals(new ItemThreshold("Rune*", 0, ItemThreshold.Inequality.MORE_THAN, true),
                ItemThreshold.fromName("Rune*"));
        assertEquals(new ItemThreshold("Bones", 0, ItemThreshold.Inequality.MORE_THAN, false),
                ItemThreshold.fromName("Bones"));
    }

    @Test
    void anEntryWithoutAQuantityMatchesAnyStackAndEmptyEntriesAreNone() {
        ItemThreshold any = ItemThreshold.fromName("Bones");
        assertTrue(any.quantityHolds(1));
        assertFalse(any.quantityHolds(0));
        assertNull(ItemThreshold.fromName(""));
        assertNull(ItemThreshold.fromName(null));
    }

    @Test
    void quantityThresholdsAreStrict() {
        ItemThreshold over = ItemThreshold.fromName("Coins > 1000");
        assertFalse(over.quantityHolds(1000));
        assertTrue(over.quantityHolds(1001));
        ItemThreshold under = ItemThreshold.fromName("Shark < 5");
        assertTrue(under.quantityHolds(4));
        assertFalse(under.quantityHolds(5));
    }

    @Test
    void anExactNameBeatsAWildcardAndTheQuantityMustHold() {
        ItemList list = new ItemList(List.of("Rune*", "Rune scimitar", "Coins > 1000", ""));
        assertEquals(ItemList.EXACT, list.matches(item("rune scimitar", 1)));
        assertEquals(ItemList.WILDCARD, list.matches(item("Rune platebody", 1)));
        assertEquals(ItemList.NONE, list.matches(item("Coins", 1000)));
        assertEquals(ItemList.EXACT, list.matches(item("Coins", 1001)));
        assertEquals(ItemList.NONE, list.matches(item("Bones", 1)));
    }

    private static GroundItem item(String name, int quantity) {
        return GroundItem.builder().name(name).quantity(quantity).build();
    }
}
