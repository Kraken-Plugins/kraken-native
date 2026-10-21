package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Draft;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupDraft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetInspectorTool}'s text: the summary a tree row shows and what a node is called.
 */
class WidgetInspectorToolTest {

    private static final WidgetNames NAMES = new WidgetNames(Map.of(149, "INVENTORY"),
            Map.of(149 << 16, new WidgetNames.Name("Inventory", "ITEMS")), Map.of(169, "COMPASS"));

    @Test
    void anItemSlotShowsItsItemAndStack() {
        assertEquals("Coins ×1,234", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "Coins", 995, 1234, "",
                -1, -1, NAMES));
        assertEquals("Shark", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "Shark", 385, 1, "", -1, -1, NAMES));
        assertEquals("item 385", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "", 385, 1, "", -1, -1, NAMES));
    }

    @Test
    void textComesBeforeNamesActionsAndSprites() {
        assertEquals("\"Quests\"", WidgetInspectorTool.summary(WidgetType.TEXT, "Quests", "Name", -1, 0, "Open",
                5, -1, NAMES));
        assertEquals("Open, Close", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "", -1, 0, "Open, Close",
                169, -1, NAMES));
        assertEquals("sprite compass", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "", -1, 0, "", 169, -1,
                NAMES));
        assertEquals("sprite 7", WidgetInspectorTool.summary(WidgetType.GRAPHIC, "", "", -1, 0, "", 7, -1, NAMES));
        assertEquals("model 42", WidgetInspectorTool.summary(WidgetType.MODEL, "", "", -1, 0, "", -1, 42, NAMES));
        assertEquals("", WidgetInspectorTool.summary(WidgetType.LAYER, "", "", -1, 0, "", -1, -1, NAMES));
    }

    @Test
    void longTextIsCut() {
        String summary = WidgetInspectorTool.summary(WidgetType.TEXT, "x".repeat(200), "", -1, 0, "", -1, -1, NAMES);

        assertTrue(summary.length() < 60, summary);
        assertTrue(summary.endsWith("…\""), summary);
    }

    @Test
    void aDynamicWidgetIsCalledByItsStaticRootsName() {
        GroupDraft group = new GroupDraft(149, "INVENTORY").drawn(new Area(0, 0, 100, 100), 0, 0, 0, 0)
                .add(new Draft(Kind.STATIC, 149 << 16, -1, WidgetType.LAYER).at(0, 0, 100, 100)
                        .text("ITEMS", "", "")
                        .add(new Draft(Kind.DYNAMIC, 149 << 16, 3, WidgetType.LAYER).at(0, 0, 50, 50)
                                .add(new Draft(Kind.DYNAMIC, 149 << 16, 7, WidgetType.TEXT).at(0, 0, 10, 10))))
                .add(new Draft(Kind.STATIC, 149 << 16 | 1, -1, WidgetType.GRAPHIC).at(0, 0, 10, 10));
        WidgetForest forest = WidgetForest.assemble(List.of(group), new Dimension(100, 100));

        assertEquals("INVENTORY", WidgetInspectorTool.title(forest.path("g149")));
        assertEquals("ITEMS", WidgetInspectorTool.title(forest.path("149:0")));
        assertEquals("ITEMS [3]", WidgetInspectorTool.title(forest.path("149:0[3]")));
        assertEquals("ITEMS [7]", WidgetInspectorTool.title(forest.path("149:0[7]")));
        assertEquals("149:1", WidgetInspectorTool.title(forest.path("149:1")), "no name: its id");
        assertEquals("", WidgetInspectorTool.title(List.of()));
    }
}
