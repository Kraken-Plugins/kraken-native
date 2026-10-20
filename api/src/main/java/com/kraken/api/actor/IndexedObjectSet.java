package com.kraken.api.actor;

import lombok.NonNull;

import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The actors of one kind in a world view, by index. Iterating reads the current list each time.
 *
 * @param <T> the actor type
 */
public final class IndexedObjectSet<T> implements Iterable<T> {

    private final Supplier<List<T>> loaded;
    private final IntFunction<? extends T> byIndex;

    /**
     * Creates the set.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param loaded  reads the actors loaded now
     * @param byIndex looks one actor up by index (null if none)
     */
    public IndexedObjectSet(Supplier<List<T>> loaded, IntFunction<? extends T> byIndex) {
        this.loaded = loaded;
        this.byIndex = byIndex;
    }

    /**
     * The actor at an index.
     *
     * @param index the actor's index
     * @return its handle, or null if nothing is loaded there
     */
    public T byIndex(int index) {
        return byIndex.apply(index);
    }

    /**
     * The actors loaded now.
     *
     * @return a stream over them
     */
    public Stream<T> stream() {
        return loaded.get().stream();
    }

    @NonNull
    @Override
    public Iterator<T> iterator() {
        return loaded.get().iterator();
    }
}
