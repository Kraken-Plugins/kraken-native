package com.kraken.client.ui.overlay;

import org.junit.jupiter.api.Test;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

import static com.kraken.client.ui.overlay.OverlayDrag.BOTTOM;
import static com.kraken.client.ui.overlay.OverlayDrag.LEFT;
import static com.kraken.client.ui.overlay.OverlayDrag.MIN_SIZE;
import static com.kraken.client.ui.overlay.OverlayDrag.RIGHT;
import static com.kraken.client.ui.overlay.OverlayDrag.TOP;
import static com.kraken.client.ui.overlay.OverlayDrag.edgesAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OverlayDrag}: which edges a press grabs, where a move or resize puts the overlay (on the canvas, no smaller
 * than the least size), and the preferred location and size it leaves on the overlay.
 */
class OverlayDragTest {

    private static final Dimension CANVAS = new Dimension(765, 503);

    private final Overlay overlay = new Overlay() {
        @Override
        public Dimension render(Graphics2D graphics) {
            return null;
        }
    };
    private final Rectangle bounds = new Rectangle(100, 50, 129, 60);

    private OverlayDrag drag(Point from, int edges) {
        return new OverlayDrag(overlay, bounds, from, edges, CANVAS);
    }

    @Test
    void aPressNearAnEdgeGrabsItAndOneInTheMiddleMoves() {
        assertEquals(0, edgesAt(bounds, new Point(160, 80)));
        assertEquals(LEFT, edgesAt(bounds, new Point(101, 80)));
        assertEquals(RIGHT, edgesAt(bounds, new Point(228, 80)));
        assertEquals(TOP | LEFT, edgesAt(bounds, new Point(100, 50)));
        assertEquals(BOTTOM | RIGHT, edgesAt(bounds, new Point(228, 109)));
        assertEquals(0, edgesAt(new Rectangle(0, 0, 8, 8), new Point(4, 4)), "a small overlay's middle still moves it");
    }

    @Test
    void aMoveFollowsThePointerButStaysOnTheCanvas() {
        OverlayDrag move = drag(new Point(160, 80), 0);
        assertEquals(new Rectangle(130, 70, 129, 60), move.to(new Point(190, 100)));
        assertEquals(new Rectangle(0, 0, 129, 60), move.to(new Point(0, 0)), "not off the top or left");
        assertEquals(new Rectangle(765 - 129, 503 - 60, 129, 60), move.to(new Point(2000, 2000)),
                "nor off the bottom or right");
    }

    @Test
    void aResizeMovesOnlyTheGrabbedEdges() {
        assertEquals(new Rectangle(100, 50, 159, 60), drag(new Point(226, 80), RIGHT).to(new Point(256, 90)));
        assertEquals(new Rectangle(80, 50, 149, 60), drag(new Point(101, 80), LEFT).to(new Point(81, 80)));
        assertEquals(new Rectangle(100, 40, 129, 70), drag(new Point(160, 51), TOP).to(new Point(160, 41)));
        assertEquals(new Rectangle(100, 50, 149, 80),
                drag(new Point(226, 107), RIGHT | BOTTOM).to(new Point(246, 127)));
    }

    @Test
    void aResizeStopsAtTheLeastSizeAndTheCanvasEdge() {
        assertEquals(new Rectangle(100, 50, MIN_SIZE, 60), drag(new Point(226, 80), RIGHT).to(new Point(0, 80)));
        assertEquals(new Rectangle(229 - MIN_SIZE, 50, MIN_SIZE, 60),
                drag(new Point(101, 80), LEFT).to(new Point(700, 80)), "the right edge stays put");
        assertEquals(new Rectangle(100, 50, 765 - 100, 60), drag(new Point(226, 80), RIGHT).to(new Point(5000, 80)));
        assertEquals(new Rectangle(0, 50, 229, 60), drag(new Point(101, 80), LEFT).to(new Point(-50, 80)));
        Rectangle small = new Rectangle(10, 10, 20, 20);
        assertEquals(small, new OverlayDrag(overlay, small, new Point(29, 29), RIGHT | BOTTOM, CANVAS)
                .to(new Point(0, 0)), "an overlay already below the least size can't shrink further");
    }

    @Test
    void draggingLeavesTheAnchorAndAResizeSetsOnlyTheDraggedSize() {
        overlay.setPreferredPosition(OverlayPosition.TOP_RIGHT);
        drag(new Point(160, 80), 0).dragTo(new Point(170, 90));
        assertNull(overlay.getPreferredPosition());
        assertEquals(new Point(110, 60), overlay.getPreferredLocation());
        assertNull(overlay.getPreferredSize(), "a move keeps the size");

        drag(new Point(226, 80), RIGHT).dragTo(new Point(236, 80));
        assertEquals(new Point(100, 50), overlay.getPreferredLocation());
        assertEquals(new Dimension(139, 0), overlay.getPreferredSize(), "the height is still the overlay's own");

        drag(new Point(160, 107), BOTTOM).dragTo(new Point(160, 117));
        assertEquals(new Dimension(139, 70), overlay.getPreferredSize(), "the width set before is kept");
    }

    @Test
    void thePointerShowsWhatAPressWouldDo() {
        assertEquals(Cursor.MOVE_CURSOR, OverlayDrag.cursor(0));
        assertEquals(Cursor.E_RESIZE_CURSOR, OverlayDrag.cursor(RIGHT));
        assertEquals(Cursor.N_RESIZE_CURSOR, OverlayDrag.cursor(TOP));
        assertEquals(Cursor.NW_RESIZE_CURSOR, OverlayDrag.cursor(LEFT | TOP));
        assertEquals(Cursor.SE_RESIZE_CURSOR, OverlayDrag.cursor(RIGHT | BOTTOM));
    }
}
