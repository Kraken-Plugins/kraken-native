package com.kraken.api.internal.event;

import com.kraken.api.events.GraphicsObjectCreated;
import com.kraken.api.events.GraphicsObjectDestroyed;
import com.kraken.api.scene.GraphicsObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link GraphicsObjectDestroyed} for each graphics object gone from the top-level world's list since the last poll,
 * then {@link GraphicsObjectCreated} for each new one, every poll. An object is known by its address and what the
 * server sent for it (id, tile, level, height, start cycle), so one that moves into the memory of an old one is still
 * new. Objects already playing when tracking starts aren't posted as created; a read that fails keeps the last one.
 */
final class GraphicsObjectTracker extends Tracker {

    /**
     * The objects of the last read, in list order.
     */
    private Set<GraphicsObject> last = Set.of();

    GraphicsObjectTracker(TrackerContext context) {
        super(context, GraphicsObjectCreated.class, GraphicsObjectDestroyed.class);
    }

    @Override
    boolean baseline(boolean replay) {
        Set<GraphicsObject> now = read();
        if (now == null) {
            return false;
        }
        last = now;
        return true;
    }

    @Override
    void update(boolean ticked) {
        Set<GraphicsObject> now = read();
        if (now == null) {
            return;
        }
        Set<GraphicsObject> previous = last;
        last = now;
        for (GraphicsObject gone : previous) {
            if (!now.contains(gone)) {
                bus.post(new GraphicsObjectDestroyed(gone));
            }
        }
        for (GraphicsObject object : now) {
            if (!previous.contains(object)) {
                bus.post(new GraphicsObjectCreated(object));
            }
        }
    }

    @Override
    void forget() {
        last = Set.of();
    }

    /**
     * Reads the list.
     *
     * @return the objects in list order; null if the list can't be read right now
     */
    private Set<GraphicsObject> read() {
        List<GraphicsObject> objects = client.getGraphicsObjectReader().graphicsObjects();
        return objects == null ? null : new LinkedHashSet<>(objects);
    }
}
