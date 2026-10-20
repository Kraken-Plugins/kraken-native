package com.kraken.api;

import java.util.stream.Stream;

/**
 * The actors of one kind in a world view, by index, as RuneLite's {@code IndexedObjectSet}. Iterating reads the
 * current list each time.
 *
 * @param <T> the actor type
 */
public interface IndexedObjectSet<T> extends Iterable<T> {

    /**
     * The actor at an index.
     *
     * @param index the actor's index
     * @return its handle, or null if nothing is loaded there
     */
    T byIndex(int index);

    /**
     * The actors loaded now.
     *
     * @return a stream over them
     */
    Stream<T> stream();
}
