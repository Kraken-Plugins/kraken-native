package com.kraken.client.ui.devtools.tools;

import com.kraken.api.gameval.InterfaceID;
import com.kraken.api.gameval.SpriteID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetNames}: the cache's names for groups, components and sprites, read from the {@code gameval} constants.
 */
class WidgetNamesTest {

    @Test
    void groupsAreNamedByTheirConstants() {
        WidgetNames names = WidgetNames.get();

        assertEquals("INVENTORY", names.group(InterfaceID.INVENTORY));
        assertEquals("", names.group(-5), "no name for an unknown group");
    }

    @Test
    void componentsAreNamedByTheirClassAndConstant() {
        WidgetNames.Name items = WidgetNames.get().component(InterfaceID.Inventory.ITEMS);

        assertEquals(new WidgetNames.Name("Inventory", "ITEMS"), items);
        assertEquals("InterfaceID.Inventory.ITEMS", items.constant());
        assertNull(WidgetNames.get().component(0xffff_ffff));
    }

    @Test
    void spritesAreNamedByTheirConstants() {
        assertEquals("COMPASS", WidgetNames.get().sprite(SpriteID.COMPASS));
        assertEquals("", WidgetNames.get().sprite(-1));
    }

    @Test
    void theNamesAreReadOnce() {
        assertSame(WidgetNames.get(), WidgetNames.get());
    }
}
