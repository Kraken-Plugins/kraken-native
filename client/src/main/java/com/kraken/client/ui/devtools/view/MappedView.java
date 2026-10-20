package com.kraken.client.ui.devtools.view;

import java.util.function.Function;
import javax.swing.*;

/**
 * Shows part of a tool's snapshot in another view, e.g. the rows of a snapshot that also carries overlay marks in a
 * {@link TableView}.
 *
 * @param <S> the tool's snapshot type
 * @param <T> what the wrapped view shows
 */
public final class MappedView<S, T> implements ToolView<S> {
    private final ToolView<T> view;
    private final Function<S, T> part;

    /**
     * Wraps a view.
     *
     * @param view the view to show the part in
     * @param part picks the part of the snapshot the view shows
     */
    public MappedView(ToolView<T> view, Function<S, T> part) {
        this.view = view;
        this.part = part;
    }

    @Override
    public JComponent component() {
        return view.component();
    }

    @Override
    public void update(S snapshot) {
        view.update(part.apply(snapshot));
    }
}
