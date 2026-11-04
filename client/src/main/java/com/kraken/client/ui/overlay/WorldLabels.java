package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.Client;
import com.kraken.api.draw.Anchor;
import com.kraken.api.draw.DrawItem;
import com.kraken.client.KrakenConfig;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Labels for the draw list ({@code Client.getDrawList()}): text drawn the way {@link OverlayUtil#renderTextLocation}
 * draws it over the scene (a black shadow one pixel down and right, the colour opaque, the overlay font's bold weight
 * at the overlay font size), as an image in framebuffer pixels, placed like {@code Perspective.getCanvasTextLocation}
 * places text: centred on the anchor with its baseline there. Images are kept and handed out again for the same text,
 * colour, font and scale, so the draw list sends each one once.
 *
 * <p>Thread-safe.
 */
@Singleton
public final class WorldLabels {

    private static final int MIN_FONT_SIZE = 6;
    private static final int MAX_FONT_SIZE = 72;
    private static final int MAX_CACHED = 1024;

    private final KrakenConfig config;
    private final Map<Key, Label> cache = new ConcurrentHashMap<>();

    /**
     * What one label image is drawn from.
     *
     * @param text   the text
     * @param colour the colour, ARGB
     * @param font   the font
     * @param scale  framebuffer pixels per canvas pixel
     */
    private record Key(String text, int colour, Font font, double scale) {
    }

    /**
     * A label image and where its top-left corner goes relative to the anchor.
     *
     * @param image   the image, in framebuffer pixels
     * @param offsetX its left edge relative to the anchor, in framebuffer pixels
     * @param offsetY its top edge relative to the anchor, in framebuffer pixels
     */
    private record Label(BufferedImage image, int offsetX, int offsetY) {
    }

    /**
     * Labels in the overlay font the settings name.
     *
     * @param config Kraken's settings
     */
    @Inject
    public WorldLabels(KrakenConfig config) {
        this.config = config;
    }

    /**
     * Framebuffer pixels per canvas pixel: how much bigger the game draws than its interface's own pixels.
     *
     * @param client the client
     * @return the scale, 1 if the sizes can't be read
     */
    public static double pixelScale(Client client) {
        int screen = client.getScreenWidth();
        int canvas = client.getCanvasWidth();
        return screen > 0 && canvas > 0 ? (double) screen / canvas : 1;
    }

    /**
     * A label for the draw list.
     *
     * @param anchor where the label goes: the middle of its baseline
     * @param text   the text
     * @param colour the text's colour, drawn opaque
     * @param scale  framebuffer pixels per canvas pixel ({@link #pixelScale})
     * @return the label, or null for null or empty text
     */
    public DrawItem.Sprite label(Anchor anchor, String text, Color colour, double scale) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        OverlayFont family = config.overlayFont() == null ? OverlayFont.REGULAR : config.overlayFont();
        int size = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, config.overlayFontSize()));
        Key key = new Key(text, colour.getRGB() | 0xff000000, family.dynamic(size), scale);
        if (cache.size() >= MAX_CACHED && !cache.containsKey(key)) {
            cache.clear();
        }
        Label label = cache.computeIfAbsent(key, WorldLabels::draw);
        return new DrawItem.Sprite(anchor, label.image(), label.offsetX(), label.offsetY());
    }

    /**
     * Draws a label image.
     *
     * @param key the text, colour, font and scale
     * @return the image and its place relative to the anchor
     */
    private static Label draw(Key key) {
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics();
        hints(probe);
        probe.setFont(key.font());
        FontMetrics metrics = probe.getFontMetrics();
        int textWidth = (int) Math.ceil(metrics.getStringBounds(key.text(), probe).getWidth());
        int ascent = metrics.getAscent();
        int descent = metrics.getDescent();
        probe.dispose();

        double scale = key.scale();
        int width = (int) Math.ceil((textWidth + 2) * scale) + 1;
        int height = (int) Math.ceil((ascent + descent + 2) * scale) + 1;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        hints(g);
        g.scale(scale, scale);
        g.setFont(key.font());
        g.setColor(Color.BLACK);
        g.drawString(key.text(), 1, ascent + 1);
        g.setColor(new Color(key.colour(), true));
        g.drawString(key.text(), 0, ascent);
        g.dispose();
        int offsetX = (int) Math.round(-(int) (textWidth / 2.0) * scale);
        int offsetY = (int) Math.round(-ascent * scale);
        return new Label(image, offsetX, offsetY);
    }

    /**
     * The rendering hints the overlay compositor draws with.
     *
     * @param g the graphics
     */
    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
