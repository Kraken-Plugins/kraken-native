package com.kraken.api.draw;

import java.util.List;
import java.util.Objects;

/**
 * One plugin's items in the {@link DrawList}. The items stay drawn, following their anchors every frame, until the
 * layer is given others, cleared or closed.
 *
 * <p>Thread-safe.
 */
public final class DrawLayer implements AutoCloseable {

    private final DrawList owner;
    private List<DrawItem> items = List.of();
    private boolean closed;

    /**
     * A layer of a draw list. Made by {@link DrawList#createLayer()}.
     *
     * @param owner the draw list
     */
    DrawLayer(DrawList owner) {
        this.owner = owner;
    }

    /**
     * Replaces the layer's items. Setting the same items again sends nothing.
     *
     * @param items the items, drawn in this order
     * @throws IllegalStateException if the layer is closed
     */
    public void set(List<? extends DrawItem> items) {
        List<DrawItem> copy = List.copyOf(Objects.requireNonNull(items, "items"));
        synchronized (owner.lock()) {
            if (closed) {
                throw new IllegalStateException("The draw layer is closed");
            }
            if (!copy.equals(this.items)) {
                this.items = copy;
                owner.changed();
            }
        }
    }

    /**
     * Removes every item.
     */
    public void clear() {
        synchronized (owner.lock()) {
            if (!closed && !items.isEmpty()) {
                items = List.of();
                owner.changed();
            }
        }
    }

    /**
     * The items the layer draws.
     *
     * @return the items
     */
    public List<DrawItem> items() {
        synchronized (owner.lock()) {
            return items;
        }
    }

    /**
     * Removes the layer and its items from the draw list. Using it afterwards throws.
     */
    @Override
    public void close() {
        synchronized (owner.lock()) {
            if (closed) {
                return;
            }
            closed = true;
            items = List.of();
            owner.closed(this);
        }
    }
}
