package com.kraken.client.ui.overlay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static com.kraken.client.ui.overlay.OverlayCompositor.BORDER;
import static com.kraken.client.ui.overlay.OverlayCompositor.BORDER_TOP;
import static com.kraken.client.ui.overlay.OverlayCompositor.PADDING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link OverlayCompositor}'s layout and drawing, into an image, headless: anchors and stacking, layers and
 * priorities, and one overlay's failure or graphics state not reaching the others.
 */
class OverlayCompositorTest {

    private static final int WIDTH = 765;
    private static final int HEIGHT = 503;
    private static final Font PANEL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font DYNAMIC = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    private final OverlayManager manager = new OverlayManager();
    private final OverlayCompositor compositor = new OverlayCompositor(PANEL, DYNAMIC);
    private final List<String> drawn = new ArrayList<>();
    private BufferedImage image;
    private Graphics2D graphics;

    /**
     * An overlay that draws a filled box of a fixed size and notes what it was drawn with.
     */
    private final class Box extends Overlay {
        private final String name;
        private final Dimension size;
        Font font;
        Color colourSeen;

        Box(String name, OverlayPosition position, int width, int height) {
            this.name = name;
            this.size = new Dimension(width, height);
            setPosition(position);
        }

        @Override
        public Dimension render(Graphics2D g) {
            drawn.add(name);
            font = g.getFont();
            colourSeen = g.getColor();
            g.setColor(Color.RED);
            g.fillRect(0, 0, size.width, size.height);
            g.translate(1000, 1000);
            return size;
        }
    }

    @BeforeEach
    void setUp() {
        image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        graphics = image.createGraphics();
    }

    @AfterEach
    void tearDown() {
        graphics.dispose();
    }

    private void frame() {
        drawn.clear();
        compositor.render(graphics, manager.getOverlays(), WIDTH, HEIGHT);
    }

    private Box add(Box box) {
        manager.add(box);
        return box;
    }

    @Test
    void anchoredOverlaysStackAtTheirCornerBySize() {
        Box first = add(new Box("first", OverlayPosition.TOP_LEFT, 100, 40));
        Box second = add(new Box("second", OverlayPosition.TOP_LEFT, 80, 30));
        frame();
        assertEquals(new Rectangle(BORDER, BORDER_TOP, 100, 40), first.getBounds());
        assertEquals(new Rectangle(BORDER, BORDER_TOP + 40 + PADDING, 80, 30), second.getBounds());
        assertEquals(Color.RED.getRGB(), image.getRGB(BORDER + 1, BORDER_TOP + 1), "drawn where it was placed");
    }

    @Test
    void rightAndBottomAnchorsUseLastFramesSize() {
        Box topRight = add(new Box("tr", OverlayPosition.TOP_RIGHT, 50, 20));
        Box bottomRight = add(new Box("br", OverlayPosition.BOTTOM_RIGHT, 40, 10));
        Box bottomRight2 = add(new Box("br2", OverlayPosition.BOTTOM_RIGHT, 30, 10));
        Box bottomLeft = add(new Box("bl", OverlayPosition.BOTTOM_LEFT, 60, 15));
        Box bottomLeft2 = add(new Box("bl2", OverlayPosition.BOTTOM_LEFT, 20, 15));
        Box centre = add(new Box("tc", OverlayPosition.TOP_CENTER, 100, 10));
        frame();
        // The first frame doesn't know the sizes yet
        assertEquals(new Point(WIDTH - BORDER, BORDER), topRight.getBounds().getLocation());
        frame();
        assertEquals(new Rectangle(WIDTH - BORDER - 50, BORDER, 50, 20), topRight.getBounds());
        assertEquals(new Rectangle(WIDTH - BORDER - 40, HEIGHT - BORDER - 10, 40, 10), bottomRight.getBounds());
        assertEquals(new Point(WIDTH - BORDER - 40 - PADDING - 30, HEIGHT - BORDER - 10),
                bottomRight2.getBounds().getLocation(), "stacks leftwards");
        assertEquals(new Rectangle(BORDER, HEIGHT - BORDER - 15, 60, 15), bottomLeft.getBounds());
        assertEquals(new Point(BORDER + 60 + PADDING, HEIGHT - BORDER - 15), bottomLeft2.getBounds().getLocation(),
                "stacks rightwards");
        assertEquals(new Point(WIDTH / 2 - 50, BORDER), centre.getBounds().getLocation());
    }

    @Test
    void anOverlayThatComesBackIsPlacedByItsLastRealSize() {
        Box box = add(new Box("tr", OverlayPosition.TOP_RIGHT, 50, 20));
        Overlay hiding = new Overlay() {
            boolean shown = true;

            @Override
            public Dimension render(Graphics2D g) {
                shown = !shown;
                return shown ? new Dimension(50, 20) : null;
            }
        };
        hiding.setPosition(OverlayPosition.BOTTOM_RIGHT);
        manager.add(hiding);
        frame();
        frame();
        assertEquals(new Dimension(50, 20), hiding.getBounds().getSize());
        frame();
        assertEquals(new Dimension(), hiding.getBounds().getSize(), "drew nothing");
        frame();
        assertEquals(new Point(WIDTH - BORDER - 50, HEIGHT - BORDER - 20), hiding.getBounds().getLocation(),
                "back in place on its first frame");
        assertEquals(WIDTH - BORDER - 50, box.getBounds().x);
    }

    @Test
    void canvasTopRightSharesTheTopRightStack() {
        Box topRight = add(new Box("tr", OverlayPosition.TOP_RIGHT, 50, 20));
        Box canvas = add(new Box("ctr", OverlayPosition.CANVAS_TOP_RIGHT, 50, 20));
        frame();
        frame();
        assertEquals(BORDER, topRight.getBounds().y);
        assertEquals(BORDER + 20 + PADDING, canvas.getBounds().y);
    }

    @Test
    void dynamicOverlaysDrawOverTheWholeCanvasWithTheirOwnFont() {
        Box dynamic = add(new Box("dynamic", OverlayPosition.DYNAMIC, 10, 10));
        Box panel = add(new Box("panel", OverlayPosition.TOP_LEFT, 10, 10));
        frame();
        assertEquals(new Point(0, 0), dynamic.getBounds().getLocation());
        assertEquals(DYNAMIC, dynamic.font);
        assertEquals(PANEL, panel.font);
    }

    @Test
    void layersDrawInOrderAndPrioritiesWithinThem() {
        Box top = add(new Box("always on top", OverlayPosition.DYNAMIC, 1, 1));
        top.setLayer(OverlayLayer.ALWAYS_ON_TOP);
        Box widgets = add(new Box("under widgets", OverlayPosition.TOP_LEFT, 1, 1));
        Box scene = add(new Box("above scene", OverlayPosition.DYNAMIC, 1, 1));
        scene.setLayer(OverlayLayer.ABOVE_SCENE);
        Box low = add(new Box("low dynamic", OverlayPosition.DYNAMIC, 1, 1));
        low.setPriority(Overlay.PRIORITY_LOW);
        Box highPanel = add(new Box("high panel", OverlayPosition.TOP_LEFT, 1, 1));
        highPanel.setPriority(Overlay.PRIORITY_HIGH);
        frame();
        // Dynamic before anchored (enum order); higher priority draws later among dynamic ones and first among
        // anchored ones, so it sits nearest the corner
        assertEquals(List.of("above scene", "low dynamic", "high panel", "under widgets", "always on top"), drawn);
        assertEquals(BORDER_TOP, highPanel.getBounds().y);
    }

    @Test
    void aFailingOverlayDoesNotStopTheOthersOrLeaveItsState() {
        Overlay failing = new Overlay() {
            @Override
            public Dimension render(Graphics2D g) {
                g.setColor(Color.GREEN);
                throw new IllegalStateException("boom");
            }
        };
        failing.setPosition(OverlayPosition.TOP_LEFT);
        manager.add(failing);
        Box first = add(new Box("first", OverlayPosition.TOP_LEFT, 10, 10));
        Box second = add(new Box("second", OverlayPosition.TOP_LEFT, 10, 10));
        graphics.setColor(Color.BLUE);
        frame();
        assertEquals(List.of("first", "second"), drawn);
        assertEquals(new Dimension(), failing.getBounds().getSize());
        assertEquals(BORDER_TOP, first.getBounds().y, "a failed overlay takes no room");
        assertEquals(Color.BLUE, first.colourSeen, "the failing overlay's colour didn't leak");
        assertEquals(Color.BLUE, second.colourSeen, "nor did the first's");
        assertEquals(Color.RED.getRGB(), image.getRGB(BORDER + 1, BORDER_TOP + 10 + PADDING + 1),
                "the first's translate didn't move the second");
    }

    @Test
    void aPreferredLocationOverridesTheAnchorUnlessAPreferredPositionIsSet() {
        Box placed = add(new Box("placed", OverlayPosition.TOP_LEFT, 10, 10));
        placed.setPreferredLocation(new Point(300, 200));
        Box anchored = add(new Box("anchored", OverlayPosition.TOP_LEFT, 10, 10));
        frame();
        assertEquals(new Point(300, 200), placed.getBounds().getLocation());
        assertEquals(new Point(BORDER, BORDER_TOP), anchored.getBounds().getLocation(), "a placed overlay isn't stacked");

        placed.setPreferredPosition(OverlayPosition.BOTTOM_LEFT);
        frame();
        assertEquals(new Point(BORDER, HEIGHT - BORDER - 10), placed.getBounds().getLocation());
    }

    @Test
    void theManagerAddsEachOverlayOnce() {
        Box box = new Box("box", OverlayPosition.TOP_LEFT, 1, 1);
        assertEquals(true, manager.add(box));
        assertEquals(false, manager.add(box));
        assertEquals(true, manager.anyMatch(o -> o == box));
        assertEquals(true, manager.removeIf(o -> o == box));
        assertEquals(false, manager.remove(box));
        assertEquals(List.of(), manager.getOverlays());
    }
}
