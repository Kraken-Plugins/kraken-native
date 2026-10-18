package com.kraken.client.devtools.view;

import javax.swing.*;

/**
 * The content of a tool's popout window. A fresh view is built each time the window opens, and it receives every
 * new snapshot while open. Reusable views: {@link TableView}, {@link FactsView}, {@link LogView}.
 *
 * @param <S> the tool's snapshot type
 */
public interface ToolView<S> {
    JComponent component();

    /** Shows a new snapshot. Runs on the EDT. */
    void update(S snapshot);
}
