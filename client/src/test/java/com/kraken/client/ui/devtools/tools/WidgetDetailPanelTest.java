package com.kraken.client.ui.devtools.tools;

import com.kraken.api.widgets.WidgetType;
import com.kraken.client.ui.devtools.tools.WidgetForest.Area;
import com.kraken.client.ui.devtools.tools.WidgetForest.Draft;
import com.kraken.client.ui.devtools.tools.WidgetForest.GroupDraft;
import com.kraken.client.ui.devtools.tools.WidgetForest.Kind;
import com.kraken.client.ui.devtools.tools.WidgetForest.Row;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetDetailPanel} as the game updates: a value that changes is updated in place, and the header is only
 * built again when what it shows changes, so the panel doesn't lay itself out again on every read.
 */
class WidgetDetailPanelTest {

    private static final WidgetNames NAMES = new WidgetNames(Map.of(149, "INVENTORY"),
            Map.of(149 << 16, new WidgetNames.Name("Inventory", "ITEMS")), Map.of());

    @Test
    void aChangedValueIsUpdatedInPlace() throws Exception {
        List<Component> before = new ArrayList<>();
        List<Component> after = new ArrayList<>();
        String[] drawn = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            WidgetDetailPanel panel = new WidgetDetailPanel(key -> {
            });
            List<Row> path = inventory(8).path("149:0[3]");
            panel.show(path, detail("in the last 2 seconds"), NAMES);
            before.addAll(all(panel));

            panel.show(inventory(9).path("149:0[3]"), detail("not in the last 2 seconds"), NAMES);
            after.addAll(all(panel));
            drawn[0] = texts(panel).stream().filter(text -> text.contains("last 2 seconds")).findFirst().orElse("");
        });

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), after.get(i), "component " + i + " was kept, not built again");
        }
        assertEquals("not in the last 2 seconds", drawn[0], "the new value shows");
    }

    @Test
    void theHeaderIsBuiltAgainWhenWhatItShowsChanges() throws Exception {
        boolean[] rebuilt = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            WidgetDetailPanel panel = new WidgetDetailPanel(key -> {
            });
            panel.show(inventory(8).path("149:0[3]"), detail("in the last 2 seconds"), NAMES);
            Component title = all(panel).get(0);
            panel.show(inventory(8).path("149:0[2]"), detail("in the last 2 seconds"), NAMES);
            rebuilt[0] = !all(panel).contains(title);
        });

        assertTrue(rebuilt[0], "another widget gets its own header");
    }

    @Test
    void theDrawnFlagIsRememberedForAWhile() {
        WidgetInspectorTool tool = new WidgetInspectorTool();

        assertFalse(tool.drawnRecently("149:0[3]", false), "never seen drawn");
        assertTrue(tool.drawnRecently("149:0[3]", true));
        assertTrue(tool.drawnRecently("149:0[3]", false), "a read that misses the drawing frame still counts it");
        assertFalse(tool.drawnRecently("149:0[4]", false), "another widget starts over");
    }

    /**
     * The inventory with four items, the layer at a given x.
     *
     * @param x the layer's x, which moves every item's bounds but nothing the header shows
     * @return the forest
     */
    private static WidgetForest inventory(int x) {
        Draft items = new Draft(Kind.STATIC, 149 << 16, -1, WidgetType.LAYER).at(x, 0, 190, 261)
                .text("ITEMS", "", "");
        for (int slot = 0; slot < 4; slot++) {
            items.add(new Draft(Kind.DYNAMIC, 149 << 16, slot, WidgetType.GRAPHIC).at(slot * 40, 0, 36, 32)
                    .text("", "Shark", ""));
        }
        GroupDraft group = new GroupDraft(149, "INVENTORY").drawn(new Area(0, 0, 800, 600), 0, 0, 0, 0).add(items);
        return WidgetForest.assemble(List.of(group), new Dimension(800, 600));
    }

    private static WidgetDetail detail(String drawn) {
        return new WidgetDetail("149:0[3]", new Area(8, 0, 36, 32), false, List.of(
                new WidgetDetail.Section("Identity", List.of(
                        new WidgetDetail.Field("Type", "GRAPHIC (5)", null, null))),
                new WidgetDetail.Section("State", List.of(
                        new WidgetDetail.Field("On screen", "yes", null, null),
                        new WidgetDetail.Field("Drawn", drawn, null, null)))));
    }

    private static List<Component> all(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel || child instanceof JTextArea || child instanceof JEditorPane) {
                found.add(child);
            }
            if (child instanceof Container container) {
                found.addAll(all(container));
            }
        }
        return found;
    }

    private static List<String> texts(Container root) {
        List<String> texts = new ArrayList<>();
        for (Component child : all(root)) {
            if (child instanceof JTextArea area) {
                texts.add(area.getText());
            }
        }
        return texts;
    }
}
