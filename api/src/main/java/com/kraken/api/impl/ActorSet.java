package com.kraken.api.impl;

import com.kraken.api.IndexedObjectSet;

import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An {@link IndexedObjectSet} that reads the current actor list on each use.
 *
 * @param <T> the actor type
 */
final class ActorSet<T> implements IndexedObjectSet<T> {

    private final Supplier<List<T>> loaded;
    private final IntFunction<? extends T> byIndex;

    /**
     * Creates the set.
     *
     * @param loaded  reads the actors loaded now
     * @param byIndex looks one actor up by index (null if none)
     */
    ActorSet(Supplier<List<T>> loaded, IntFunction<? extends T> byIndex) {
        this.loaded = loaded;
        this.byIndex = byIndex;
    }

    @Override
    public T byIndex(int index) {
        return byIndex.apply(index);
    }

    @Override
    public Stream<T> stream() {
        return loaded.get().stream();
    }

    @Override
    public Iterator<T> iterator() {
        return loaded.get().iterator();
    }
}
