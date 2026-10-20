package com.kraken.client.ui.overlay.components;

import com.kraken.client.ui.overlay.OverlayPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;

import static com.kraken.client.ui.overlay.components.ComponentConstants.STANDARD_BORDER;
import static com.kraken.client.ui.overlay.components.ComponentConstants.STANDARD_WIDTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PanelComponent} and its lines, headless: sizes from the font, a panel that grows for a wide line, a background
 * that fits this frame's lines, and {@link OverlayPanel} clearing its children each frame.
 */
class PanelComponentTest {

    private BufferedImage image;
    private Graphics2D graphics;
    private FontMetrics metrics;

    @BeforeEach
    void setUp() {
        image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        graphics = image.createGraphics();
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        metrics = graphics.getFontMetrics();
    }

    @AfterEach
    void tearDown() {
        graphics.dispose();
    }

    @Test
    void aPanelIsItsLinesPlusTheBorder() {
        PanelComponent panel = new PanelComponent();
        panel.setPreferredLocation(new Point(10, 20));
        panel.getChildren().add(TitleComponent.builder().text("Title").build());
        panel.getChildren().add(LineComponent.builder().left("Ticks:").right("12").build());
        Dimension size = panel.render(graphics);

        int lines = metrics.getHeight() * 2 + 1;
        assertEquals(new Dimension(STANDARD_WIDTH, lines + 2 * STANDARD_BORDER), size);
        assertEquals(new Point(10, 20), panel.getBounds().getLocation());
        assertEquals(ComponentConstants.STANDARD_BACKGROUND_COLOR.getAlpha(),
                image.getRGB(12, 20 + size.height - 3) >>> 24, "the background covers the last line's row");
        assertEquals(0, image.getRGB(10, 20 + size.height + 1) >>> 24, "and stops at its edge");
    }

    @Test
    void aWideLineWidensThePanelAndPushesValuesRight() {
        PanelComponent panel = new PanelComponent();
        String wide = "A label far wider than the standard panel width";
        LineComponent short1 = LineComponent.builder().left("A").right("1").build();
        panel.getChildren().add(short1);
        panel.getChildren().add(LineComponent.builder().left(wide).right("2").build());
        Dimension size = panel.render(graphics);

        int inner = metrics.stringWidth(wide) + 8 + metrics.stringWidth("2");
        assertEquals(inner + 2 * STANDARD_BORDER, size.width);
        assertEquals(inner, short1.getBounds().width, "every line is laid out at the widest one's width");
    }

    @Test
    void anEmptyPanelDrawsNothing() {
        assertNull(new PanelComponent().render(graphics));
    }

    @Test
    void anOverlayPanelStartsEachFrameEmpty() {
        OverlayPanel overlay = new OverlayPanel() {
            @Override
            public Dimension render(Graphics2D g) {
                panelComponent.getChildren().add(LineComponent.builder().left("Line").build());
                return super.render(g);
            }
        };
        Dimension first = overlay.render(graphics);
        Dimension second = overlay.render(graphics);
        assertEquals(first, second, "the second frame's line replaced the first's");

        overlay.setPreferredSize(new Dimension(200, 0));
        assertEquals(200, overlay.render(graphics).width);
        overlay.setPreferredSize(null);
        assertEquals(STANDARD_WIDTH, overlay.render(graphics).width, "the panel's own width came back");
    }
}
