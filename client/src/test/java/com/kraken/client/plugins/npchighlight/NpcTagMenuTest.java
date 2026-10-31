package com.kraken.client.plugins.npchighlight;

import com.kraken.api.menu.MenuRow;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rows {@link NpcTagMenu} puts above an NPC's Examine row, what clicking each does, and the colours it offers.
 */
class NpcTagMenuTest {

    private static final String GOBLIN = "<col=ffff00>Goblin<col=ff00>  (level-2)";

    private final List<String> calls = new ArrayList<>();
    private final NpcTagMenu.Actions actions = new NpcTagMenu.Actions(
            () -> calls.add("tag"),
            () -> calls.add("tagAll"),
            color -> calls.add("color " + Integer.toHexString(color.getRGB())),
            () -> calls.add("pick"),
            () -> calls.add("resetColor"),
            style -> calls.add("style " + style),
            () -> calls.add("resetStyle"));

    @Test
    void theStateTellsAListedNameFromAWildcardMatch() {
        NpcTagMenu.State listed = NpcTagMenu.state(List.of("goblin"), "Goblin", false, false, false);
        assertTrue(listed.listed());
        assertFalse(listed.wildcard());
        NpcTagMenu.State wildcard = NpcTagMenu.state(List.of("gob*"), "Goblin", false, false, false);
        assertFalse(wildcard.listed());
        assertTrue(wildcard.wildcard());
        NpcTagMenu.State both = NpcTagMenu.state(List.of("Goblin", "*"), "Goblin", false, false, false);
        assertTrue(both.listed());
        assertTrue(both.wildcard());
        NpcTagMenu.State tagged = NpcTagMenu.state(List.of(), "Goblin", true, false, false);
        assertTrue(tagged.highlighted());
        assertFalse(NpcTagMenu.state(List.of("cow"), "Goblin", false, false, false).highlighted());
    }

    @Test
    void anNpcNotHighlightedOffersTagAndTagAll() {
        List<MenuRow> rows = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(false, false, false, false, false),
                List.of(), actions);
        assertEquals(List.of("Tag", "Tag-All"), options(rows));
        assertEquals(GOBLIN, rows.get(0).target(), "the rows name the NPC as its Examine row does");
        rows.get(0).onClick().accept(null);
        rows.get(1).onClick().accept(null);
        assertEquals(List.of("tag", "tagAll"), calls);
    }

    @Test
    void aHighlightedNpcOffersUntagAndItsColourAndStyle() {
        List<MenuRow> tagged = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(true, false, false, false, false),
                List.of(Color.RED), actions);
        assertEquals(List.of("Un-tag", "Tag-All", "Tag color", "Tag style"), options(tagged));
        List<MenuRow> listed = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(false, true, false, false, false),
                List.of(Color.RED), actions);
        assertEquals(List.of("Tag", "Un-tag-All", "Tag color", "Tag style"), options(listed));
    }

    @Test
    void aWildcardMatchOffersNoTagAll() {
        List<MenuRow> rows = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(false, false, true, false, false),
                List.of(), actions);
        assertEquals(List.of("Tag", "Tag color", "Tag style"), options(rows),
                "Un-tag-All can't take a wildcard pattern out");
    }

    @Test
    void theTagColorSubMenuSetsPicksAndResets() {
        List<MenuRow> plain = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(true, false, false, false, false),
                List.of(Color.RED, Color.BLUE), actions).get(2).children();
        assertEquals(List.of("<col=ff0000>Set color", "<col=0000ff>Set color", "Pick color"), options(plain));
        List<MenuRow> own = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(true, false, false, true, false),
                List.of(Color.RED), actions).get(2).children();
        assertEquals(List.of("<col=ff0000>Set color", "Pick color", "Reset"), options(own));
        for (MenuRow row : own) {
            row.onClick().accept(null);
        }
        assertEquals(List.of("color ffff0000", "pick", "resetColor"), calls);
    }

    @Test
    void theTagStyleSubMenuSetsEachStyleByItsSavedName() {
        List<MenuRow> styles = NpcTagMenu.rows(GOBLIN, new NpcTagMenu.State(true, false, false, false, true),
                List.of(), actions).get(3).children();
        assertEquals(List.of("Hull", "Tile", "True tile", "South-west tile", "South-west true tile", "Outline",
                "Reset"), options(styles));
        for (MenuRow row : styles) {
            row.onClick().accept(null);
        }
        assertEquals(List.of("style hull", "style tile", "style truetile", "style swtile", "style swtruetile",
                "style outline", "resetStyle"), calls);
    }

    @Test
    void colourChoicesStartWithThoseInUseAndFillUpToFive() {
        assertEquals(List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA),
                NpcTagMenu.colorChoices(List.of()));
        Color orange = new Color(255, 128, 0);
        assertEquals(List.of(orange, Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW),
                NpcTagMenu.colorChoices(List.of(orange, Color.BLUE)), "a default in use isn't offered twice");
        List<Color> many = List.of(new Color(1), new Color(2), new Color(3), new Color(4), new Color(5),
                new Color(6));
        assertEquals(many.subList(0, 5), NpcTagMenu.colorChoices(many));
    }

    private static List<String> options(List<MenuRow> rows) {
        return rows.stream().map(MenuRow::option).toList();
    }
}
