package com.kraken.client.plugins.npchighlight;

import com.kraken.api.coords.WorldPoint;
import com.kraken.client.plugins.npchighlight.NpcIndicatorsConfig.MenuHighlightStyle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's name matching, highlight list toggling, menu target colouring and view range.
 */
class NpcIndicatorsPluginTest {

    private static final String RAM = "<col=ffff00>Ram<col=ff00>  (level-2)";

    @Test
    void namesMatchWholePatternsWithWildcardsIgnoringCase() {
        List<String> patterns = List.of("goblin", "*dragon", "Guard*");
        assertTrue(NpcIndicatorsPlugin.matchesAny(patterns, "Goblin"));
        assertTrue(NpcIndicatorsPlugin.matchesAny(patterns, "Green dragon"));
        assertTrue(NpcIndicatorsPlugin.matchesAny(patterns, "Guard captain"));
        assertFalse(NpcIndicatorsPlugin.matchesAny(patterns, "Goblin champion"), "the pattern must match in full");
        assertFalse(NpcIndicatorsPlugin.matchesAny(List.of(), "Goblin"));
        assertTrue(NpcIndicatorsPlugin.matchesAny(List.of("a.b"), "A.B"), "a dot is literal");
        assertFalse(NpcIndicatorsPlugin.matchesAny(List.of("a.b"), "axb"));
    }

    @Test
    void togglingANameAddsItOrTakesItOutIgnoringCase() {
        assertEquals(List.of("Goblin", "Cow"), NpcIndicatorsPlugin.toggleName(List.of("Goblin"), "Cow"));
        assertEquals(List.of("Cow"), NpcIndicatorsPlugin.toggleName(List.of("goblin", "Cow"), "Goblin"));
        assertEquals(List.of("Cow"), NpcIndicatorsPlugin.toggleName(List.of(), "Cow"));
    }

    @Test
    void menuTargetsAreColouredByStyle() {
        assertEquals(RAM, NpcIndicatorsPlugin.recolorMenuTarget(RAM, MenuHighlightStyle.NONE, Color.RED));
        assertEquals("<col=ff0000>Ram  (level-2)",
                NpcIndicatorsPlugin.recolorMenuTarget(RAM, MenuHighlightStyle.BOTH, Color.RED));
        assertEquals("<col=ff0000>Ram<col=ff00>  (level-2)",
                NpcIndicatorsPlugin.recolorMenuTarget(RAM, MenuHighlightStyle.NAME, Color.RED));
        assertEquals("<col=ffff00>Ram<col=ff0000>  (level-2)",
                NpcIndicatorsPlugin.recolorMenuTarget(RAM, MenuHighlightStyle.LEVEL, Color.RED));
        assertEquals("<col=ffff00>Tool Leprechaun", NpcIndicatorsPlugin.recolorMenuTarget(
                "<col=ffff00>Tool Leprechaun", MenuHighlightStyle.LEVEL, Color.RED), "no level to colour");
        assertEquals("<col=ff0000>Tool Leprechaun", NpcIndicatorsPlugin.recolorMenuTarget(
                "<col=ffff00>Tool Leprechaun", MenuHighlightStyle.NAME, Color.RED));
    }

    @Test
    void viewRangeIsUnderFifteenTilesOnThePlane() {
        WorldPoint here = new WorldPoint(3200, 3200, 0);
        assertTrue(NpcIndicatorsPlugin.isInViewRange(here, new WorldPoint(3214, 3190, 0)));
        assertFalse(NpcIndicatorsPlugin.isInViewRange(here, new WorldPoint(3215, 3200, 0)));
        assertFalse(NpcIndicatorsPlugin.isInViewRange(here, new WorldPoint(3200, 3200, 1)));
    }
}
