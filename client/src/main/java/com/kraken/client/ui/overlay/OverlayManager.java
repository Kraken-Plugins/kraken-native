package com.kraken.client.ui.overlay;

import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The overlays being drawn, as RuneLite's {@code OverlayManager}. Plugins add theirs in {@code startUp} and remove them
 * in {@code shutDown}; the client draws every added overlay at the overlay frame rate while the game is attached, and
 * draws nothing (and reads nothing) while none is added.
 *
 * <p>Thread-safe: add and remove on any thread.
 */
@Singleton
public class OverlayManager {

    /**
     * The order overlays draw in within a layer, as RuneLite's: by position, then by priority. Higher priority draws
     * first among anchored overlays (so it sits nearest the corner) and last among dynamic ones (so it's on top).
     */
    static final Comparator<Overlay> OVERLAY_COMPARATOR = (a, b) -> Key.ORDER.compare(
            new Key(a, effectivePosition(a), a.getPriority()), new Key(b, effectivePosition(b), b.getPriority()));

    private final CopyOnWriteArrayList<Overlay> overlays = new CopyOnWriteArrayList<>();

    /**
     * Starts drawing an overlay.
     *
     * @param overlay the overlay
     * @return true if it was added; false if it was already there
     */
    public boolean add(Overlay overlay) {
        return overlays.addIfAbsent(overlay);
    }

    /**
     * Stops drawing an overlay.
     *
     * @param overlay the overlay
     * @return true if it was there
     */
    public boolean remove(Overlay overlay) {
        return overlays.remove(overlay);
    }

    /**
     * Stops drawing every overlay that matches.
     *
     * @param filter which overlays to remove
     * @return true if any was removed
     */
    public boolean removeIf(Predicate<Overlay> filter) {
        return overlays.removeIf(filter);
    }

    /**
     * Whether any overlay being drawn matches.
     *
     * @param filter the test
     * @return true if one matches
     */
    public boolean anyMatch(Predicate<Overlay> filter) {
        return overlays.stream().anyMatch(filter);
    }

    /**
     * Every overlay being drawn, in drawing order within each layer.
     *
     * @return a sorted copy
     */
    public List<Overlay> getOverlays() {
        // Sort on a snapshot of each overlay's position and priority: they can change on another thread mid-sort,
        // which would break the comparator's contract
        List<Key> keys = new ArrayList<>(overlays.size());
        for (Overlay overlay : overlays) {
            keys.add(new Key(overlay, effectivePosition(overlay), overlay.getPriority()));
        }
        keys.sort(Key.ORDER);
        List<Overlay> sorted = new ArrayList<>(keys.size());
        for (Key key : keys) {
            sorted.add(key.overlay());
        }
        return sorted;
    }

    /**
     * What an overlay is sorted by, read once.
     *
     * @param overlay  the overlay
     * @param position where it's laid out
     * @param priority its priority
     */
    private record Key(Overlay overlay, OverlayPosition position, float priority) {
        static final Comparator<Key> ORDER = (a, b) -> {
            if (a.position != b.position) {
                return a.position.compareTo(b.position);
            }
            return a.position == OverlayPosition.DYNAMIC
                    ? Float.compare(a.priority, b.priority)
                    : Float.compare(b.priority, a.priority);
        };
    }

    /**
     * Where an overlay is laid out: its preferred position if it has one, else its position.
     *
     * @param overlay the overlay
     * @return the position it's drawn at
     */
    static OverlayPosition effectivePosition(Overlay overlay) {
        OverlayPosition preferred = overlay.getPreferredPosition();
        return preferred != null ? preferred : overlay.getPosition();
    }
}
