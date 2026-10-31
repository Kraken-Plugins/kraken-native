package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.client.plugins.config.ConfigManager;

import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The overlays being drawn. Plugins add theirs in {@code startUp} and remove them in {@code shutDown}; the client draws
 * every added overlay while the game is attached (with each game frame inside the game, up to the overlay frame rate),
 * and draws nothing (and reads nothing) while none is added.
 *
 * <p>It also keeps where the user put each overlay ({@link OverlayEditor}): its preferred location and size are saved
 * in settings group {@value #CONFIG_GROUP}, under the overlay's {@linkplain Overlay#getName() name}, and set again
 * whenever an overlay of that name is added.
 *
 * <p>Thread-safe: add and remove on any thread.
 */
@Singleton
public class OverlayManager {

    /**
     * The settings group overlays' placements are saved in.
     */
    public static final String CONFIG_GROUP = "overlays";

    private static final String LOCATION_KEY = "_preferredLocation";
    private static final String SIZE_KEY = "_preferredSize";

    /**
     * The order overlays draw in within a layer: by position, then by priority. Higher priority draws first among
     * anchored overlays (so it sits nearest the corner) and last among dynamic ones (so it's on top).
     */
    static final Comparator<Overlay> OVERLAY_COMPARATOR = (a, b) -> Key.ORDER.compare(
            new Key(a, effectivePosition(a), a.getPriority()), new Key(b, effectivePosition(b), b.getPriority()));

    private final CopyOnWriteArrayList<Overlay> overlays = new CopyOnWriteArrayList<>();
    private final ConfigManager configManager;

    /**
     * An overlay manager that saves where the user puts overlays.
     *
     * @param configManager where placements are saved
     */
    @Inject
    public OverlayManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * An overlay manager that saves no placements.
     */
    OverlayManager() {
        this(null);
    }

    /**
     * Starts drawing an overlay, where the user last put it if they moved or resized it.
     *
     * @param overlay the overlay
     * @return true if it was added; false if it was already there
     */
    public boolean add(Overlay overlay) {
        if (!overlays.addIfAbsent(overlay)) {
            return false;
        }
        loadOverlay(overlay);
        return true;
    }

    /**
     * Saves where an overlay is placed now: its preferred location and size, or that it has neither.
     *
     * @param overlay the overlay
     */
    public void saveOverlay(Overlay overlay) {
        if (configManager != null) {
            configManager.setConfiguration(CONFIG_GROUP, overlay.getName() + LOCATION_KEY,
                    overlay.getPreferredLocation());
            configManager.setConfiguration(CONFIG_GROUP, overlay.getName() + SIZE_KEY, overlay.getPreferredSize());
        }
    }

    /**
     * Puts an overlay back at its anchor and natural size, and forgets where the user put it.
     *
     * @param overlay the overlay
     */
    public void resetOverlay(Overlay overlay) {
        overlay.setPreferredPosition(null);
        overlay.setPreferredLocation(null);
        overlay.setPreferredSize(null);
        saveOverlay(overlay);
    }

    /**
     * Sets an overlay's preferred location and size to the saved ones, where there are any.
     *
     * @param overlay the overlay
     */
    private void loadOverlay(Overlay overlay) {
        if (configManager == null) {
            return;
        }
        Point location = configManager.getConfiguration(CONFIG_GROUP, overlay.getName() + LOCATION_KEY, Point.class);
        if (location != null) {
            overlay.setPreferredLocation(location);
        }
        Dimension size = configManager.getConfiguration(CONFIG_GROUP, overlay.getName() + SIZE_KEY, Dimension.class);
        if (size != null) {
            overlay.setPreferredSize(size);
        }
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
     * Whether no overlay is being drawn. Cheaper than {@link #getOverlays()}, which sorts a copy.
     *
     * @return true while none is added
     */
    public boolean isEmpty() {
        return overlays.isEmpty();
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
