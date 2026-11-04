package com.kraken.api.internal.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AtlasPacker}: sprites placed along shelves below the reserved rows, a pixel apart, until the atlas is full.
 */
class AtlasPackerTest {

    @Test
    void spritesGoLeftToRightAPixelApartBelowTheReservedRows() {
        AtlasPacker packer = new AtlasPacker(64, 4);
        assertEquals(new AtlasPacker.Place(0, 5), packer.place(10, 8));
        assertEquals(new AtlasPacker.Place(11, 5), packer.place(20, 12));
        assertEquals(new AtlasPacker.Place(32, 5), packer.place(31, 3));
    }

    @Test
    void aSpriteThatDoesntFitTheShelfStartsANewOneBelowItsTallest() {
        AtlasPacker packer = new AtlasPacker(64, 4);
        packer.place(40, 8);
        packer.place(10, 12);
        assertEquals(new AtlasPacker.Place(0, 5 + 12 + 1), packer.place(30, 4));
    }

    @Test
    void aFullAtlasPlacesNothingUntilReset() {
        AtlasPacker packer = new AtlasPacker(16, 0);
        assertEquals(new AtlasPacker.Place(0, 1), packer.place(16, 14));
        assertNull(packer.place(16, 2), "no rows left");
        assertNull(packer.place(17, 1), "wider than the atlas");
        packer.reset();
        assertEquals(new AtlasPacker.Place(0, 1), packer.place(4, 4));
    }
}
