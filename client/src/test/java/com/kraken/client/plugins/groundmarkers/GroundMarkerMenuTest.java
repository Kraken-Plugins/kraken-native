package com.kraken.client.plugins.groundmarkers;

import com.kraken.api.menu.MenuRow;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rows {@link GroundMarkerMenu} puts below a tile's Walk here row, and what clicking each does.
 */
class GroundMarkerMenuTest {

    private static final int LUMBRIDGE = 12850;
    private static final GroundMarkerPoint RED_MARK = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, Color.RED, null);

    private final List<String> calls = new ArrayList<>();
    private final GroundMarkerMenu.Actions actions = new GroundMarkerMenu.Actions(
            () -> calls.add("mark"),
            () -> calls.add("label"),
            color -> calls.add("color " + Integer.toHexString(color.getRGB())),
            () -> calls.add("pick"),
            () -> calls.add("reset"));

    @Test
    void anUnmarkedTileOffersMarkOnly() {
        List<MenuRow> rows = GroundMarkerMenu.rows(null, 3, List.of(Color.BLUE), actions);
        assertEquals(List.of("Mark Tile"), texts(rows));
        rows.get(0).onClick().accept(null);
        assertEquals(List.of("mark"), calls);
    }

    @Test
    void aMarkedTileOffersUnmarkLabelAndColor() {
        List<MenuRow> rows = GroundMarkerMenu.rows(RED_MARK, 1, List.of(Color.RED), actions);
        assertEquals(List.of("Unmark Tile", "Label Tile", "Color Tile"), texts(rows));
        assertNull(rows.get(2).onClick(), "the Color row only opens its sub-menu");
        rows.get(0).onClick().accept(null);
        rows.get(1).onClick().accept(null);
        assertEquals(List.of("mark", "label"), calls);
    }

    @Test
    void theColorSubMenuOffersTheOtherColoursInUseThenPick() {
        List<MenuRow> rows = GroundMarkerMenu.rows(RED_MARK, 1, List.of(Color.RED, Color.BLUE, Color.GREEN),
                actions);
        List<MenuRow> colors = rows.get(2).children();
        assertEquals(List.of("<col=0000ff>Color", "<col=00ff00>Color", "Pick"),
                colors.stream().map(MenuRow::option).toList(), "the mark's own colour isn't offered");
        colors.get(1).onClick().accept(null);
        colors.get(2).onClick().accept(null);
        assertEquals(List.of("color ff00ff00", "pick"), calls);
    }

    @Test
    void resetAllIsOfferedWhenTheRegionHasOtherMarks() {
        List<MenuRow> alone = GroundMarkerMenu.rows(RED_MARK, 1, List.of(), actions).get(2).children();
        assertEquals(List.of("Pick"), alone.stream().map(MenuRow::option).toList());
        List<MenuRow> several = GroundMarkerMenu.rows(RED_MARK, 2, List.of(), actions).get(2).children();
        assertEquals(List.of("Pick", "Reset all"), several.stream().map(MenuRow::option).toList());
        several.get(1).onClick().accept(null);
        assertEquals(List.of("reset"), calls);
    }

    @Test
    void aMarkInTheTileColourIsOfferedEveryColourInUse() {
        GroundMarkerPoint uncoloured = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, null, null);
        List<MenuRow> colors = GroundMarkerMenu.rows(uncoloured, 1, List.of(Color.RED), actions).get(2).children();
        assertEquals(List.of("<col=ff0000>Color", "Pick"), colors.stream().map(MenuRow::option).toList());
    }

    @Test
    void theColorSubMenuKeepsWithinTheRowLimit() {
        List<Color> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new Color(i, 0, 0));
        }
        List<MenuRow> colors = GroundMarkerMenu.rows(RED_MARK, 2, many, actions).get(2).children();
        assertEquals(MenuRow.MAX_CHILDREN, colors.size());
        assertEquals("Pick", colors.get(MenuRow.MAX_CHILDREN - 2).option());
        assertEquals("Reset all", colors.get(MenuRow.MAX_CHILDREN - 1).option());
    }

    private static List<String> texts(List<MenuRow> rows) {
        return rows.stream().map(row -> row.option() + " " + row.target()).toList();
    }
}
