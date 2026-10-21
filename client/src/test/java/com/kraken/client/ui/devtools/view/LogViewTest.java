package com.kraken.client.ui.devtools.view;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LogView}'s filters: the kind dropdown lists kinds as they arrive, and the dropdown and text filter apply to
 * past and new lines alike.
 */
class LogViewTest {

    /**
     * Builds a view whose line kind is the line's first word.
     *
     * @return the view
     */
    private static LogView view() {
        return new LogView("events", line -> line.split(" ")[0]);
    }

    @Test
    void dropdownListsKindsSortedAfterAll() throws Exception {
        LogView[] view = new LogView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = view();
            view[0].update(List.of("NpcSpawned a", "GameTick 1", "NpcDespawned a", "GameTick 2"));
        });
        JComboBox<?> kinds = find(view[0].component(), JComboBox.class).get(0);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < kinds.getItemCount(); i++) {
            items.add(kinds.getItemAt(i));
        }
        assertEquals(List.of("All", "GameTick", "NpcDespawned", "NpcSpawned"), items);
    }

    @Test
    void kindAndTextFiltersApplyToPastAndNewLines() throws Exception {
        LogView[] view = new LogView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = view();
            view[0].update(List.of("NpcSpawned Goblin", "GameTick 1", "NpcSpawned Cow"));
        });
        JComboBox<?> kinds = find(view[0].component(), JComboBox.class).get(0);
        JTextField filter = find(view[0].component(), JTextField.class).get(0);
        JTextArea text = find(view[0].component(), JTextArea.class).get(0);

        SwingUtilities.invokeAndWait(() -> kinds.setSelectedItem("NpcSpawned"));
        assertEquals("NpcSpawned Goblin\nNpcSpawned Cow\n", text.getText(), "kind filter on past lines");

        SwingUtilities.invokeAndWait(() -> view[0].update(List.of("GameTick 2", "NpcSpawned Imp")));
        assertEquals("NpcSpawned Goblin\nNpcSpawned Cow\nNpcSpawned Imp\n", text.getText(), "kind filter on new lines");

        SwingUtilities.invokeAndWait(() -> filter.setText("cow"));
        assertEquals("NpcSpawned Cow\n", text.getText(), "text filter is case-insensitive");

        SwingUtilities.invokeAndWait(() -> {
            filter.setText("");
            kinds.setSelectedItem("All");
        });
        assertEquals("NpcSpawned Goblin\nGameTick 1\nNpcSpawned Cow\nGameTick 2\nNpcSpawned Imp\n", text.getText());
    }

    private static <T extends Component> List<T> find(Container root, Class<T> type) {
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
