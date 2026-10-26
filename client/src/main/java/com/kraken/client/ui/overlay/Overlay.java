package com.kraken.client.ui.overlay;

import com.kraken.client.plugins.Plugin;
import lombok.Getter;
import lombok.Setter;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Something a plugin draws over the game. Subclass it, implement {@link #render}, and add it to the
 * {@link OverlayManager} (usually in {@code startUp}; remove it in {@code shutDown}).
 *
 * <p>{@link #render} runs on the client thread, the thread game events and {@code ClientThread} tasks run on, so
 * plugin state shared between them needs no locking. It draws in the client's canvas pixels, the units
 * {@code Perspective} and {@code Actor.getCanvasTilePoly} return, so their results draw as they are.
 *
 * <p>While the user holds Alt over the game, every {@linkplain #isMovable() movable} overlay that isn't
 * {@link OverlayPosition#DYNAMIC} is outlined and can be dragged to a new spot, and a {@linkplain #isResizable()
 * resizable} one resized by its edges; Alt and a right-click puts it back at its anchor and natural size
 * ({@link OverlayEditor}). Where the user put it is saved and restored whenever it is added again
 * ({@link OverlayManager}). Left out, because the game owns the mouse: snapping to an anchor, menu entries and
 * mouse-over.
 */
public abstract class Overlay implements LayoutableRenderableEntity {

    /**
     * The lowest priority.
     */
    public static final float PRIORITY_LOW = 0f;

    /**
     * The default priority.
     */
    public static final float PRIORITY_DEFAULT = 0.25f;

    /**
     * A medium priority.
     */
    public static final float PRIORITY_MED = 0.5f;

    /**
     * A high priority.
     */
    public static final float PRIORITY_HIGH = 0.75f;

    /**
     * The highest priority.
     */
    public static final float PRIORITY_HIGHEST = 1f;

    /**
     * The plugin the overlay belongs to, or null.
     */
    @Getter
    private final Plugin plugin;

    /**
     * Where and how big the overlay was last drawn, in canvas pixels. The renderer sets it.
     */
    @Getter
    private final Rectangle bounds = new Rectangle();

    /**
     * Where the overlay goes.
     */
    @Getter
    @Setter
    private volatile OverlayPosition position = OverlayPosition.TOP_LEFT;

    /**
     * An anchor to use instead of {@link #getPosition()}, or null. Only code sets it; overlays can't be snapped to a
     * corner by the user. Dragging an overlay clears it.
     */
    @Getter
    @Setter
    private volatile OverlayPosition preferredPosition;

    /**
     * Where to draw a non-{@link OverlayPosition#DYNAMIC} overlay instead of at its anchor (its top-left corner in
     * canvas pixels), or null. Used unless {@link #getPreferredPosition()} is set; kept inside the canvas. The user
     * sets it by dragging the overlay.
     */
    @Getter
    @Setter
    private volatile Point preferredLocation;

    /**
     * The size the overlay should draw at, or null for its natural size. {@link OverlayPanel} lays its panel out to it.
     * The user sets it by resizing the overlay; a height of 0 leaves the height to the overlay.
     */
    @Getter
    @Setter
    private volatile Dimension preferredSize;

    /**
     * Whether the user can drag the overlay with Alt held. {@link OverlayPosition#DYNAMIC} overlays can't be dragged
     * whatever this says.
     */
    @Getter
    @Setter
    private volatile boolean movable = true;

    /**
     * Whether the user can resize the overlay by its edges with Alt held. The new size goes to
     * {@link #getPreferredSize()}, so only an overlay that draws at that size should allow it; {@link OverlayPanel}
     * does.
     */
    @Getter
    @Setter
    private volatile boolean resizable;

    /**
     * Where the overlay goes among those at the same anchor: higher draws first, so nearer the corner. Among
     * {@link OverlayPosition#DYNAMIC} overlays higher draws last, so on top.
     */
    @Getter
    @Setter
    private volatile float priority = PRIORITY_DEFAULT;

    /**
     * When the overlay draws relative to the others.
     */
    @Getter
    @Setter
    private volatile OverlayLayer layer = OverlayLayer.UNDER_WIDGETS;

    /**
     * An overlay that belongs to no plugin.
     */
    protected Overlay() {
        this(null);
    }

    /**
     * An overlay belonging to a plugin.
     *
     * @param plugin the plugin, or null
     */
    protected Overlay(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The overlay's name, for the log.
     *
     * @return the class's simple name
     */
    public String getName() {
        return getClass().getSimpleName();
    }
}
