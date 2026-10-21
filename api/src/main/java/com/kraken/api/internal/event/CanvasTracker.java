package com.kraken.api.internal.event;

import com.kraken.api.events.CanvasSizeChanged;

import java.awt.Dimension;
import java.util.Set;

/**
 * {@link CanvasSizeChanged} when the canvas's size or the window's changes, as Lua's {@code ON_SCREEN_RESIZE}.
 */
final class CanvasTracker extends Tracker {

    private static final CanvasSizeChanged CANVAS_SIZE_CHANGED = new CanvasSizeChanged();

    private Dimension lastDimensions;
    private Dimension lastScreen;

    CanvasTracker(TrackerContext context) {
        super(context, CanvasSizeChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_SCREEN_RESIZE");
    }

    @Override
    boolean baseline(boolean replay) {
        Dimension dimensions = canvas();
        if (dimensions == null) {
            return false;
        }
        lastDimensions = dimensions;
        lastScreen = screen();
        return true;
    }

    @Override
    void update(boolean ticked) {
        Dimension dimensions = canvas();
        if (dimensions == null) {
            return;
        }
        Dimension screen = screen();
        boolean changed = !dimensions.equals(lastDimensions)
                || screen != null && lastScreen != null && !screen.equals(lastScreen);
        lastDimensions = dimensions;
        if (screen != null) {
            lastScreen = screen;
        }
        if (changed) {
            bus.post(CANVAS_SIZE_CHANGED);
        }
    }

    @Override
    void forget() {
        lastDimensions = null;
        lastScreen = null;
    }

    private Dimension canvas() {
        int width = client.getCanvasWidth();
        int height = client.getCanvasHeight();
        return width < 0 || height < 0 ? null : new Dimension(width, height);
    }

    private Dimension screen() {
        int width = client.getScreenWidth();
        int height = client.getScreenHeight();
        return width < 0 || height < 0 ? null : new Dimension(width, height);
    }
}
