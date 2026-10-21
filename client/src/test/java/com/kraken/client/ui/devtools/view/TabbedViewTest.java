package com.kraken.client.ui.devtools.view;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TabbedView}: every tab's view gets every snapshot, the chosen tab opens first, and picking a tab is reported.
 */
class TabbedViewTest {

    /**
     * A view that records what it was shown.
     */
    private static final class Recording implements ToolView<String> {
        private final JPanel panel = new JPanel();
        private final List<String> seen = new ArrayList<>();

        @Override
        public JComponent component() {
            return panel;
        }

        @Override
        public void update(String snapshot) {
            seen.add(snapshot);
        }
    }

    @Test
    void everyTabSeesEverySnapshotAndPickingATabIsReported() throws Exception {
        Recording table = new Recording();
        Recording slots = new Recording();
        List<Integer> picked = new ArrayList<>();
        TabbedView<?>[] view = new TabbedView<?>[1];
        SwingUtilities.invokeAndWait(() -> {
            TabbedView<String> tabs = TabbedView.<String>builder()
                    .tab("Table", MaterialDesignT.TABLE, table)
                    .tab("Slots", MaterialDesignV.VIEW_GRID_OUTLINE, slots)
                    .selected(1)
                    .onSelect(picked::add)
                    .build();
            tabs.update("a");
            tabs.update("b");
            view[0] = tabs;
        });
        assertEquals(List.of("a", "b"), table.seen);
        assertEquals(List.of("a", "b"), slots.seen);

        List<JToggleButton> buttons = find(view[0].component(), JToggleButton.class);
        assertTrue(buttons.get(1).isSelected(), "opens on the chosen tab");
        assertTrue(slots.panel.isVisible() && !table.panel.isVisible(), "shows the chosen tab's view");

        SwingUtilities.invokeAndWait(() -> buttons.get(0).doClick());
        assertEquals(List.of(0), picked);
        assertTrue(table.panel.isVisible() && !slots.panel.isVisible(), "shows the picked tab's view");
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(find(container, type));
            }
        }
        return found;
    }
}
